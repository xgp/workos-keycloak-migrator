package io.phasetwo.migration.common.sync;

import io.phasetwo.client.openapi.model.OrganizationRepresentation;
import io.phasetwo.migration.common.AttributeKeys;
import io.phasetwo.migration.common.keycloak.Lookups;
import io.phasetwo.migration.common.util.JsonUtil;
import io.phasetwo.migration.common.workos.model.WDirectory;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.jbosslog.JBossLog;

/**
 * Creates a stub SCIM provider in the Phase Two org. The PT admin client does not expose typed
 * mutators for the SCIM resource at this version, so we fall through to a direct REST call via
 * Keycloak's underlying JAX-RS proxy.
 */
@JBossLog
public class DirectorySync implements EntitySync<WDirectory> {
  private static final String ENTITY = "directory";

  private final SyncContext ctx;
  private final String serverUrl;

  public DirectorySync(SyncContext ctx, String serverUrl) {
    this.ctx = ctx;
    this.serverUrl = serverUrl.replaceAll("/+$", "");
  }

  @Override
  public SyncResult sync(WDirectory d) {
    if (d == null || d.id() == null) {
      return SyncResult.failed(ENTITY, null, "missing id");
    }
    Optional<OrganizationRepresentation> org =
        Lookups.orgByWorkosId(ctx.phaseTwo(), ctx.realmName(), d.organizationId());
    if (org.isEmpty()) {
      return SyncResult.skipped(ENTITY, d.id(), "org_not_yet_migrated:" + d.organizationId());
    }
    // Tag the org with directory metadata
    Map<String, List<String>> attrs =
        org.get().getAttributes() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(org.get().getAttributes());
    attrs.put(AttributeKeys.WORKOS_DIRECTORY_ID, List.of(d.id()));
    attrs.put(AttributeKeys.WORKOS_DIRECTORY_TYPE, List.of(d.type() == null ? "" : d.type()));
    attrs.put(AttributeKeys.WORKOS_DIRECTORY_STATE, List.of(d.state() == null ? "" : d.state()));
    attrs.put(AttributeKeys.WORKOS_DIRECTORY_INCOMPLETE, List.of("true"));
    org.get().setAttributes(attrs);
    ctx.phaseTwo().organizations(ctx.realmName()).organization(org.get().getId()).update(org.get());

    if (ctx.dryRun()) return SyncResult.skipped(ENTITY, d.id(), "dry_run");

    // POST /realms/{realm}/orgs/{orgId}/scim
    String url = serverUrl + "/realms/" + ctx.realmName() + "/orgs/" + org.get().getId() + "/scim";
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("enabled", d.isLinked());
    body.put("email_as_username", true);
    body.put("link_idp", false);
    Map<String, Object> auth = new LinkedHashMap<>();
    auth.put("type", "EXTERNAL_SECRET");
    // Bcrypt-style PHC placeholder so the field passes validation; admin must replace.
    auth.put("shared_secret", "$argon2id$v=19$m=65536,t=3,p=4$WORKOSPLACEHOLDER$WORKOSPLACEHOLDER");
    body.put("auth", auth);

    try {
      String token = ctx.keycloak().tokenManager().getAccessTokenString();
      try (Response resp =
          jakarta.ws.rs.client.ClientBuilder.newClient()
              .target(url)
              .request(MediaType.APPLICATION_JSON)
              .header("Authorization", "Bearer " + token)
              .post(
                  Entity.entity(
                      JsonUtil.mapper().writeValueAsString(body), MediaType.APPLICATION_JSON))) {
        int code = resp.getStatus();
        if (code == 201 || code == 200) {
          return SyncResult.partial(ENTITY, d.id(), d.id(), "scim_stub_created");
        }
        if (code == 409) {
          return SyncResult.skipped(ENTITY, d.id(), "scim_exists");
        }
        String msg = resp.hasEntity() ? resp.readEntity(String.class) : "";
        log.warnf("SCIM stub create failed: %s %s", code, msg);
        return SyncResult.failed(ENTITY, d.id(), "scim_create_" + code);
      }
    } catch (Exception e) {
      log.errorf("SCIM stub create error: %s", e.toString());
      return SyncResult.failed(ENTITY, d.id(), e.toString());
    }
  }
}
