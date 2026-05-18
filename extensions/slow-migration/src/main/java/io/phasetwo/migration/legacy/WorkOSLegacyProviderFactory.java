package io.phasetwo.migration.legacy;

import com.google.auto.service.AutoService;
import io.phasetwo.migration.common.AttributeKeys;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.Config;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;

@AutoService(RealmResourceProviderFactory.class)
@JBossLog
public class WorkOSLegacyProviderFactory implements RealmResourceProviderFactory {
  static final String ID = "workos-legacy";

  /**
   * Provider id of the user-federation factory shipped by daniel-frak/keycloak-user-migration. Per
   * the SPEC clarification round, this is the registered providerId in the upstream JAR.
   */
  static final String LEGACY_PROVIDER_ID = "User migration using a REST client";

  private String apiKey;
  private String clientId;
  private String clientSecret;
  private String apiBaseUrl;
  private String serviceBaseUrl;

  /**
   * Realms eligible for postInit provisioning. Empty = none (the default). {@code *} = every realm.
   * Otherwise a comma-separated allowlist. Set via provider config {@code realms} (env var {@code
   * KC_SPI_REALM_RESTAPI_EXTENSION_WORKOS_LEGACY_REALMS}).
   */
  private List<String> realmFilter;

  private boolean realmWildcard;
  private String componentName;

  @Override
  public RealmResourceProvider create(KeycloakSession session) {
    return new WorkOSLegacyProvider(session);
  }

  @Override
  public void init(Config.Scope config) {
    this.apiKey = configOrEnv(config, "apiKey", "WORKOS_API_KEY");
    this.clientId = configOrEnv(config, "clientId", "WORKOS_CLIENT_ID");
    this.clientSecret = configOrEnv(config, "clientSecret", "WORKOS_CLIENT_SECRET");
    this.apiBaseUrl = configOrDefault(config, "apiBaseUrl", "https://api.workos.com");
    this.serviceBaseUrl = configOrEnv(config, "serviceBaseUrl", "KC_HOSTNAME_URL");
    // Default: empty list → postInit skips every realm. Operators must opt in by setting
    // `realms` to either a comma-separated allowlist or `*` for "all realms".
    String realms = configOrDefault(config, "realms", "");
    if ("*".equals(realms.trim())) {
      this.realmWildcard = true;
      this.realmFilter = List.of();
    } else {
      this.realmWildcard = false;
      this.realmFilter =
          realms.isBlank()
              ? List.of()
              : Arrays.stream(realms.split(","))
                  .map(String::trim)
                  .filter(s -> !s.isEmpty())
                  .collect(Collectors.toList());
    }
    this.componentName = configOrDefault(config, "componentName", "workos-legacy-migration");
  }

  @Override
  public void postInit(KeycloakSessionFactory factory) {
    if (!realmWildcard && realmFilter.isEmpty()) {
      log.info(
          "workos-legacy: no realms enabled for auto-provisioning. Set provider config 'realms=*' or a comma-separated list (env "
              + "KC_SPI_REALM_RESTAPI_EXTENSION_WORKOS_LEGACY_REALMS) to opt in.");
      return;
    }
    Thread t = new Thread(() -> provisionAllRealms(factory), "workos-legacy-provisioner");
    t.setDaemon(true);
    t.start();
  }

  void provisionAllRealms(KeycloakSessionFactory factory) {
    try {
      KeycloakSession s = factory.create();
      try {
        s.getTransactionManager().begin();
        s.realms()
            .getRealmsStream()
            .forEach(
                realm -> {
                  if (!realmWildcard && !realmFilter.contains(realm.getName())) return;
                  try {
                    provisionRealm(realm, s);
                  } catch (Exception e) {
                    log.warnf("provision failed for realm %s: %s", realm.getName(), e.toString());
                  }
                });
        s.getTransactionManager().commit();
      } finally {
        s.close();
      }
    } catch (Throwable t) {
      log.errorf("workos-legacy provisioning errored: %s", t.toString());
    }
  }

  private void provisionRealm(RealmModel realm, KeycloakSession session) {
    if ("master".equals(realm.getName())) {
      return;
    }
    // Persist API credentials on the realm so the resource (which runs in admin-less request
    // threads) can read them.
    if (apiKey != null) realm.setAttribute("workos.migration.api_key", apiKey);
    if (apiBaseUrl != null) realm.setAttribute("workos.migration.api_base_url", apiBaseUrl);
    if (clientId != null) realm.setAttribute(AttributeKeys.REALM_SLOW_CLIENT_ID, clientId);
    if (clientSecret != null)
      realm.setAttribute(AttributeKeys.REALM_SLOW_CLIENT_SECRET, clientSecret);

    String token = realm.getAttribute(AttributeKeys.REALM_SLOW_TOKEN);
    if (token == null || token.isBlank()) {
      byte[] buf = new byte[32];
      new SecureRandom().nextBytes(buf);
      token = Base64.getUrlEncoder().withoutPadding().encodeToString(buf);
      realm.setAttribute(AttributeKeys.REALM_SLOW_TOKEN, token);
    }

    // Look for an existing federation component with our name; create if missing.
    Optional<ComponentModel> existing =
        realm
            .getComponentsStream(realm.getId(), "org.keycloak.storage.UserStorageProvider")
            .filter(c -> componentName.equals(c.getName()))
            .findFirst();
    if (existing.isPresent()) {
      log.infof(
          "realm %s: legacy migration component already present (id=%s)",
          realm.getName(), existing.get().getId());
      return;
    }
    ComponentModel cm = new ComponentModel();
    cm.setName(componentName);
    cm.setProviderType("org.keycloak.storage.UserStorageProvider");
    cm.setProviderId(LEGACY_PROVIDER_ID);
    cm.setParentId(realm.getId());
    // Config keys per the upstream README
    String resourceUri = buildResourceUri(session, realm);
    if (resourceUri != null) {
      cm.getConfig().putSingle("URI", resourceUri);
    }
    cm.getConfig().putSingle("API_TOKEN_ENABLED", "true");
    cm.getConfig().putSingle("API_TOKEN", token);
    cm.getConfig().putSingle("USE_USER_ID_FOR_CREDENTIAL_VERIFICATION", "false");
    cm.getConfig().putSingle("UPDATE_USER_ON_LOGIN", "true");
    try {
      realm.addComponentModel(cm);
      log.infof("realm %s: registered legacy migration component", realm.getName());
    } catch (Exception e) {
      log.warnf(
          "realm %s: could not register legacy migration component: %s",
          realm.getName(), e.toString());
    }
  }

  private String buildResourceUri(KeycloakSession session, RealmModel realm) {
    String base = serviceBaseUrl;
    if (base == null) {
      try {
        if (session.getContext() != null && session.getContext().getUri() != null) {
          base = session.getContext().getUri().getBaseUri().toString();
        }
      } catch (Exception ignored) {
        // No HTTP request bound — fall through.
      }
    }
    if (base == null) base = System.getenv("KC_HOSTNAME_URL");
    if (base == null) return null;
    return base.replaceAll("/+$", "") + "/realms/" + realm.getName() + "/" + ID;
  }

  @Override
  public void close() {}

  @Override
  public String getId() {
    return ID;
  }

  private static String configOrDefault(Config.Scope cfg, String key, String def) {
    String v = cfg.get(key);
    return v == null || v.isBlank() ? def : v;
  }

  private static String configOrEnv(Config.Scope cfg, String key, String envVar) {
    String v = cfg.get(key);
    if (v != null && !v.isBlank()) return v;
    return System.getenv(envVar);
  }
}
