package io.phasetwo.migration.webhook;

import lombok.extern.jbosslog.JBossLog;
import com.google.auto.service.AutoService;
import io.phasetwo.migration.common.AttributeKeys;
import io.phasetwo.migration.common.workos.WorkOSHttpClient;
import io.phasetwo.migration.common.workos.model.WWebhookEndpoint;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import org.keycloak.Config;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.services.resource.RealmResourceProvider;
import org.keycloak.services.resource.RealmResourceProviderFactory;
@AutoService(RealmResourceProviderFactory.class)
@JBossLog
public class WorkOSWebhookProviderFactory implements RealmResourceProviderFactory {
    static final String ID = "workos-webhook";

    /** Events the listener subscribes to in WorkOS. */
    static final List<String> SUBSCRIBED_EVENTS = List.of(
            "user.created",
            "user.updated",
            "user.deleted",
            "organization.created",
            "organization.updated",
            "organization.deleted",
            "organization_membership.created",
            "organization_membership.updated",
            "organization_membership.deleted",
            "organization_domain.created",
            "organization_domain.updated",
            "organization_domain.verified",
            "organization_domain.deleted",
            "role.created",
            "role.updated",
            "role.deleted",
            "organization_role.created",
            "organization_role.updated",
            "organization_role.deleted",
            "connection.activated",
            "connection.deactivated",
            "connection.deleted",
            "connection.saml_certificate_renewed",
            "dsync.activated",
            "dsync.deleted",
            "dsync.group.created",
            "dsync.group.updated",
            "dsync.group.deleted",
            "dsync.user.created",
            "dsync.user.updated",
            "dsync.user.deleted");

    private String apiKey;
    private String apiBaseUrl;
    private String webhookBaseUrl;
    private long eventToleranceMillis;
    /**
     * Realms eligible for postInit provisioning. Empty = none (the default). {@code *} = every
     * realm. Otherwise a comma-separated allowlist. Set via the {@code realms} provider config
     * (env var {@code KC_SPI_REALM_RESTAPI_EXTENSION_WORKOS_WEBHOOK_REALMS}).
     */
    private List<String> realmFilter;
    /** True iff {@link #realmFilter} contains the wildcard. */
    private boolean realmWildcard;
    private boolean provisionWebhook;

    @Override
    public RealmResourceProvider create(KeycloakSession session) {
        return new WorkOSWebhookProvider(session, eventToleranceMillis);
    }

    @Override
    public void init(Config.Scope config) {
        this.apiKey = configOrEnv(config, "apiKey", "WORKOS_API_KEY");
        this.apiBaseUrl = configOrDefault(config, "apiBaseUrl", "https://api.workos.com");
        this.webhookBaseUrl = configOrEnv(config, "webhookBaseUrl", "KC_HOSTNAME_URL");
        this.eventToleranceMillis = Long.parseLong(configOrDefault(config, "eventTolerance", "300")) * 1000L;
        // Default: empty list → postInit skips every realm. Operators must opt in by setting
        // `realms` to either a comma-separated allowlist or `*` for "all realms".
        String realms = configOrDefault(config, "realms", "");
        if ("*".equals(realms.trim())) {
            this.realmWildcard = true;
            this.realmFilter = List.of();
        } else {
            this.realmWildcard = false;
            this.realmFilter = realms.isBlank()
                    ? List.of()
                    : Arrays.stream(realms.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        }
        this.provisionWebhook = Boolean.parseBoolean(configOrDefault(config, "provisionWebhook", "true"));
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        if (apiKey == null || apiKey.isBlank() || !provisionWebhook) {
            log.info("workos-webhook: provisioning disabled or no API key set; only serving traffic");
            return;
        }
        if (!realmWildcard && realmFilter.isEmpty()) {
            log.info(
                    "workos-webhook: no realms enabled for auto-provisioning. Set provider config 'realms=*' or a comma-separated list (env "
                            + "KC_SPI_REALM_RESTAPI_EXTENSION_WORKOS_WEBHOOK_REALMS) to opt in.");
            return;
        }
        Thread t = new Thread(() -> provisionAllRealms(factory), "workos-webhook-provisioner");
        t.setDaemon(true);
        t.start();
    }

    void provisionAllRealms(KeycloakSessionFactory factory) {
        try {
            KeycloakSession s = factory.create();
            try {
                s.getTransactionManager().begin();
                s.realms().getRealmsStream().forEach(realm -> {
                    if (!realmWildcard && !realmFilter.contains(realm.getName())) return;
                    try {
                        provisionRealm(realm);
                    } catch (Exception e) {
                        log.warnf("provision failed for realm %s: %s", realm.getName(), e.toString());
                    }
                });
                s.getTransactionManager().commit();
            } finally {
                s.close();
            }
        } catch (Throwable t) {
            log.errorf("workos-webhook provisioning errored: %s", t.toString());
        }
    }

    private void provisionRealm(RealmModel realm) {
        if ("master".equals(realm.getName())) {
            // Don't auto-register the master realm — it's not a migration target.
            return;
        }
        String publicId = realm.getAttribute(AttributeKeys.REALM_WEBHOOK_PUBLIC_ID);
        if (publicId == null || publicId.isBlank()) {
            publicId = UUID.randomUUID().toString();
            realm.setAttribute(AttributeKeys.REALM_WEBHOOK_PUBLIC_ID, publicId);
        }
        String base = webhookBaseUrl == null ? null : webhookBaseUrl.replaceAll("/+$", "");
        if (base == null || base.isBlank()) {
            String frontend = realm.getAttribute("frontendUrl");
            if (frontend != null && !frontend.isBlank()) base = frontend.replaceAll("/+$", "");
        }
        if (base == null || base.isBlank()) {
            log.warnf("realm %s: no public base URL — set spi-realm-restapi-extension-workos-webhook-webhook-base-url or realm attribute frontendUrl", realm.getName());
            return;
        }
        String endpointUrl = base + "/realms/" + realm.getName() + "/" + ID + "/" + publicId;
        if (!endpointUrl.startsWith("https://")) {
            log.warnf("realm %s: WorkOS requires an HTTPS endpoint; skipping provisioning (computed url: %s). "
                    + "Set the realm attribute workos.migration.webhook.secret manually for local testing.", realm.getName(), endpointUrl);
            return;
        }

        WorkOSHttpClient w = new WorkOSHttpClient(apiBaseUrl, apiKey);
        List<WWebhookEndpoint> existing = w.listWebhookEndpoints();
        for (WWebhookEndpoint ep : existing) {
            if (endpointUrl.equals(ep.endpointUrl())) {
                realm.setAttribute(AttributeKeys.REALM_WEBHOOK_ENDPOINT_ID, ep.id());
                if (ep.secret() != null) realm.setAttribute(AttributeKeys.REALM_WEBHOOK_SECRET, ep.secret());
                if (!new HashSet<>(ep.events()).equals(new HashSet<>(SUBSCRIBED_EVENTS))) {
                    w.updateWebhookEndpoint(ep.id(), SUBSCRIBED_EVENTS);
                    log.infof("realm %s: updated WorkOS webhook events", realm.getName());
                }
                log.infof("realm %s: reusing WorkOS webhook %s", realm.getName(), ep.id());
                return;
            }
        }
        WWebhookEndpoint created = w.createWebhookEndpoint(endpointUrl, SUBSCRIBED_EVENTS);
        realm.setAttribute(AttributeKeys.REALM_WEBHOOK_ENDPOINT_ID, created.id());
        if (created.secret() != null) realm.setAttribute(AttributeKeys.REALM_WEBHOOK_SECRET, created.secret());
        log.infof("realm %s: created WorkOS webhook %s at %s", realm.getName(), created.id(), endpointUrl);
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
