package io.phasetwo.migration.common.sync;

import io.phasetwo.client.openapi.model.IdentityProviderRepresentation;
import io.phasetwo.client.openapi.model.OrganizationRepresentation;
import io.phasetwo.migration.common.AttributeKeys;
import io.phasetwo.migration.common.keycloak.Lookups;
import io.phasetwo.migration.common.workos.model.WConnection;
import java.util.*;
import lombok.extern.jbosslog.JBossLog;

@JBossLog
public class IdentityProviderSync implements EntitySync<WConnection> {
  private static final String ENTITY = "identity_provider";

  private final SyncContext ctx;

  public IdentityProviderSync(SyncContext ctx) {
    this.ctx = ctx;
  }

  @Override
  public SyncResult sync(WConnection c) {
    if (c == null || c.id() == null) {
      return SyncResult.failed(ENTITY, null, "missing id");
    }
    String mappedProviderId = mapProviderId(c.connectionType());
    if (mappedProviderId == null) {
      return SyncResult.skipped(ENTITY, c.id(), "unmapped_connection_type:" + c.connectionType());
    }

    Optional<OrganizationRepresentation> org =
        Lookups.orgByWorkosId(ctx.phaseTwo(), ctx.realmName(), c.organizationId());
    if (org.isEmpty()) {
      return SyncResult.skipped(ENTITY, c.id(), "org_not_yet_migrated:" + c.organizationId());
    }

    String alias = "workos-" + c.id();
    IdentityProviderRepresentation rep = new IdentityProviderRepresentation();
    rep.setAlias(alias);
    rep.setDisplayName(c.name());
    rep.setProviderId(mappedProviderId);
    rep.setEnabled(c.isActive());
    rep.setLinkOnly(false);
    Map<String, Object> cfg = new LinkedHashMap<>();
    cfg.put(AttributeKeys.WORKOS_IDP_CONNECTION_ID, c.id());
    cfg.put(AttributeKeys.WORKOS_IDP_CONNECTION_TYPE, c.connectionType());
    boolean incomplete = true;
    if ("saml".equals(mappedProviderId)
        && c.options() != null
        && c.options().signingCert() != null) {
      cfg.put("signingCertificate", c.options().signingCert());
      cfg.put("validateSignature", "true");
    }
    // We never have client_id/secret/SSO URLs from /connections — always start as a stub.
    cfg.put(AttributeKeys.WORKOS_IDP_INCOMPLETE, Boolean.toString(incomplete));
    rep.setConfig(cfg);

    if (ctx.dryRun()) return SyncResult.skipped(ENTITY, c.id(), "dry_run");

    try {
      ctx.phaseTwo()
          .organizations(ctx.realmName())
          .organization(org.get().getId())
          .identityProviders()
          .get(alias);
      ctx.phaseTwo()
          .organizations(ctx.realmName())
          .organization(org.get().getId())
          .identityProviders()
          .update(alias, rep);
      return SyncResult.partial(ENTITY, c.id(), alias, "incomplete_config").withKeycloakId(alias);
    } catch (jakarta.ws.rs.NotFoundException e) {
      String created =
          ctx.phaseTwo()
              .organizations(ctx.realmName())
              .organization(org.get().getId())
              .identityProviders()
              .create(rep);
      return SyncResult.partial(ENTITY, c.id(), alias, "incomplete_config")
          .withKeycloakId(created != null ? created : alias);
    } catch (Exception e) {
      log.errorf("idp sync failed for %s: %s", c.id(), e.toString());
      return SyncResult.failed(ENTITY, c.id(), e.toString());
    }
  }

  static String mapProviderId(String connectionType) {
    if (connectionType == null) return null;
    if (connectionType.endsWith("SAML")) return "saml";
    if (connectionType.endsWith("OIDC") || connectionType.endsWith("Oidc")) return "oidc";
    return switch (connectionType) {
      case "GoogleOAuth" -> "google";
      case "MicrosoftOAuth" -> "microsoft";
      case "GitHubOAuth" -> "github";
      case "GitLabOAuth" -> "gitlab";
      case "LinkedInOAuth" -> "linkedin-openid-connect";
      case "AppleOAuth" -> "apple";
      case "SlackOAuth",
          "DiscordOAuth",
          "BitbucketOAuth",
          "XeroOAuth",
          "IntuitOAuth",
          "SalesforceOAuth",
          "VercelOAuth",
          "VercelMarketplaceOAuth" ->
          "oidc";
      case "Auth0Migration", "MagicLink", "TestIdp", "Pending" -> null;
      default -> null;
    };
  }
}
