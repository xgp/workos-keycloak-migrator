package io.phasetwo.migration.common.sync;

import io.phasetwo.migration.common.AttributeKeys;
import io.phasetwo.migration.common.keycloak.Lookups;
import io.phasetwo.migration.common.util.Hashes;
import io.phasetwo.migration.common.util.JsonUtil;
import io.phasetwo.migration.common.workos.model.WIdentity;
import io.phasetwo.migration.common.workos.model.WUser;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.representations.idm.FederatedIdentityRepresentation;
import org.keycloak.representations.idm.IdentityProviderRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

@JBossLog
public class UserSync implements EntitySync<WUser> {
  private static final String ENTITY = "user";

  private final SyncContext ctx;

  public UserSync(SyncContext ctx) {
    this.ctx = ctx;
  }

  @Override
  public SyncResult sync(WUser u) {
    if (u == null || u.email() == null) {
      return SyncResult.failed(ENTITY, u == null ? null : u.id(), "missing email");
    }

    Optional<UserRepresentation> existing = Lookups.userByWorkosId(ctx.realm(), u.id());
    Optional<UserRepresentation> byEmail =
        existing.isPresent() ? existing : Lookups.userByEmail(ctx.realm(), u.email());

    // Conflict: by-email match without matching workos.id
    if (existing.isEmpty() && byEmail.isPresent()) {
      UserRepresentation match = byEmail.get();
      String tagged = attr(match, AttributeKeys.WORKOS_ID);
      if (tagged != null && !tagged.isEmpty() && !tagged.equals(u.id())) {
        log.warnf(
            "conflict: KC user %s carries workos.id=%s but WorkOS supplied %s",
            match.getId(), tagged, u.id());
        return SyncResult.skipped(ENTITY, u.id(), "email_conflict_with_different_workos_id");
      }
    }

    UserRepresentation target = byEmail.orElseGet(UserRepresentation::new);
    target.setUsername(u.email());
    target.setEmail(u.email());
    target.setEmailVerified(Boolean.TRUE.equals(u.emailVerified()));
    target.setFirstName(u.firstName());
    target.setLastName(u.lastName());
    target.setEnabled(true);
    if (u.createdAt() != null && target.getCreatedTimestamp() == null) {
      try {
        target.setCreatedTimestamp(OffsetDateTime.parse(u.createdAt()).toInstant().toEpochMilli());
      } catch (Exception ignored) {
      }
    }

    Map<String, List<String>> attrs =
        target.getAttributes() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(target.getAttributes());
    putSingle(attrs, AttributeKeys.WORKOS_ID, u.id());
    putSingle(attrs, AttributeKeys.WORKOS_EXTERNAL_ID, u.externalId());
    putSingle(attrs, AttributeKeys.WORKOS_PROFILE_PICTURE_URL, u.profilePictureUrl());
    putSingle(attrs, AttributeKeys.WORKOS_LOCALE, u.locale());
    putSingle(attrs, AttributeKeys.WORKOS_LAST_SIGN_IN_AT, u.lastSignInAt());
    putSingle(attrs, AttributeKeys.WORKOS_SOURCE, ctx.source());
    if (u.metadata() != null) {
      u.metadata().forEach((k, v) -> putSingle(attrs, AttributeKeys.WORKOS_METADATA_PREFIX + k, v));
    }
    if (!attrs.containsKey(AttributeKeys.WORKOS_MIGRATED_AT)) {
      putSingle(attrs, AttributeKeys.WORKOS_MIGRATED_AT, Instant.now().toString());
    }
    putSingle(attrs, AttributeKeys.WORKOS_LAST_SYNC_AT, Instant.now().toString());

    // Hash-based no-op detection (exclude last_sync_at to avoid false drift)
    String hashSeed = JsonUtil.mapper().valueToTree(Map.of("u", u, "src", ctx.source())).toString();
    String hash = Hashes.sha256Hex(hashSeed);
    String prevHash = attrs.getOrDefault(AttributeKeys.WORKOS_SYNC_HASH, List.of("")).get(0);
    boolean noChange = existing.isPresent() && hash.equals(prevHash);
    putSingle(attrs, AttributeKeys.WORKOS_SYNC_HASH, hash);

    target.setAttributes(attrs);

    // requiredActions
    List<String> required =
        target.getRequiredActions() == null
            ? new ArrayList<>()
            : new ArrayList<>(target.getRequiredActions());
    if (!ctx.slowMigrationActive()) {
      if (!required.contains("UPDATE_PASSWORD")) required.add("UPDATE_PASSWORD");
    } else {
      required.remove("UPDATE_PASSWORD");
    }
    target.setRequiredActions(required);

    // Federated identities
    List<WIdentity> identities;
    try {
      identities = ctx.workos().listUserIdentities(u.id());
    } catch (Exception e) {
      log.warnf("could not load identities for %s: %s", u.id(), e.toString());
      identities = List.of();
    }
    List<FederatedIdentityRepresentation> fis = new ArrayList<>();
    for (WIdentity id : identities) {
      String alias = mapProviderToAlias(id.provider());
      ensureLinkOnlyIdp(alias, id.provider());
      FederatedIdentityRepresentation fi = new FederatedIdentityRepresentation();
      fi.setIdentityProvider(alias);
      fi.setUserId(id.idpId());
      fi.setUserName(u.email());
      fis.add(fi);
    }
    target.setFederatedIdentities(fis);

    if (ctx.dryRun()) {
      return existing.isPresent()
          ? SyncResult.skipped(ENTITY, u.id(), "dry_run").withReason("dry_run")
          : SyncResult.skipped(ENTITY, u.id(), "dry_run");
    }

    if (existing.isPresent()) {
      if (noChange) {
        return SyncResult.skipped(ENTITY, u.id(), "unchanged")
            .withKeycloakId(existing.get().getId());
      }
      target.setId(existing.get().getId());
      ctx.realm().users().get(existing.get().getId()).update(target);
      return SyncResult.updated(ENTITY, u.id(), existing.get().getId());
    }

    // Create
    try (Response resp = ctx.realm().users().create(target)) {
      int status = resp.getStatus();
      if (status == 201) {
        String location = resp.getHeaderString("Location");
        String kcId = location == null ? null : location.substring(location.lastIndexOf('/') + 1);
        return SyncResult.created(ENTITY, u.id(), kcId);
      }
      if (status == 409) {
        return SyncResult.skipped(ENTITY, u.id(), "conflict_during_create");
      }
      return SyncResult.failed(ENTITY, u.id(), "create returned " + status);
    }
  }

  private void ensureLinkOnlyIdp(String alias, String provider) {
    if (Lookups.idpByAlias(ctx.realm(), alias).isPresent()) return;
    IdentityProviderRepresentation rep = new IdentityProviderRepresentation();
    rep.setAlias(alias);
    rep.setDisplayName(alias);
    rep.setProviderId(providerIdForOauth(provider));
    rep.setLinkOnly(true);
    rep.setEnabled(false);
    Map<String, String> cfg = new LinkedHashMap<>();
    cfg.put(AttributeKeys.WORKOS_IDP_INCOMPLETE, "true");
    cfg.put(AttributeKeys.WORKOS_IDP_CONNECTION_TYPE, provider);
    rep.setConfig(cfg);
    try {
      ctx.realm().identityProviders().create(rep).close();
    } catch (Exception e) {
      log.debugf("could not create stub idp %s: %s", alias, e.toString());
    }
  }

  private static String mapProviderToAlias(String provider) {
    return "oauth-"
        + (provider == null ? "unknown" : provider.toLowerCase(Locale.ROOT).replace("oauth", ""));
  }

  private static String providerIdForOauth(String provider) {
    if (provider == null) return "oidc";
    return switch (provider) {
      case "GoogleOAuth" -> "google";
      case "MicrosoftOAuth" -> "microsoft";
      case "GithubOAuth" -> "github";
      case "GitLabOAuth" -> "gitlab";
      case "LinkedInOAuth" -> "linkedin-openid-connect";
      case "AppleOAuth" -> "apple";
      default -> "oidc";
    };
  }

  private static void putSingle(Map<String, List<String>> attrs, String key, String value) {
    if (value == null) return;
    attrs.put(key, List.of(value));
  }

  private static String attr(UserRepresentation u, String key) {
    if (u.getAttributes() == null) return null;
    List<String> v = u.getAttributes().get(key);
    return v == null || v.isEmpty() ? null : v.get(0);
  }
}
