package io.phasetwo.migration.common.sync;

import io.phasetwo.migration.common.AttributeKeys;
import io.phasetwo.migration.common.keycloak.Lookups;
import io.phasetwo.migration.common.keycloak.Roles;
import io.phasetwo.migration.common.workos.model.WDirectoryUser;
import io.phasetwo.migration.common.workos.model.WUser;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Links a WorkOS {@link WDirectoryUser} (a SCIM-provisioned user) to its existing Keycloak user
 * record. The mapping path:
 *
 * <ol>
 *   <li>Resolve the matching KC user by email — DirectoryUser and the WorkOS User Management record
 *       share the same email.
 *   <li>If the KC user is missing, look up the WorkOS user by email via the {@code
 *       /user_management/users} endpoint, sync it, then retry. We don't try to create directly from
 *       DirectoryUser data because the User Management record is the canonical source for federated
 *       identities / metadata.
 *   <li>Grant the {@code scim-managed} realm role.
 *   <li>Set user attributes {@code scim.directory_id}, {@code scim.directory_user_id}, {@code
 *       scim.idp_id}, {@code scim.state}.
 * </ol>
 */
@JBossLog
public class DirectoryUserSync implements EntitySync<WDirectoryUser> {
  private static final String ENTITY = "directory_user";

  private final SyncContext ctx;

  public DirectoryUserSync(SyncContext ctx) {
    this.ctx = ctx;
  }

  @Override
  public SyncResult sync(WDirectoryUser d) {
    if (d == null || d.id() == null) {
      return SyncResult.failed(ENTITY, d == null ? null : null, "missing id");
    }
    if (d.email() == null || d.email().isBlank()) {
      return SyncResult.skipped(ENTITY, d.id(), "no_email");
    }
    Optional<UserRepresentation> existing = Lookups.userByEmail(ctx.realm(), d.email());
    if (existing.isEmpty()) {
      // Fall back: pull the User Management record and sync it first, then retry.
      Optional<WUser> wu = ctx.workos().findUserByEmail(d.email());
      if (wu.isEmpty()) {
        return SyncResult.skipped(ENTITY, d.id(), "no_matching_kc_user");
      }
      new UserSync(ctx).sync(wu.get());
      existing = Lookups.userByEmail(ctx.realm(), d.email());
      if (existing.isEmpty()) {
        return SyncResult.skipped(ENTITY, d.id(), "user_sync_did_not_materialise");
      }
    }
    UserRepresentation user = existing.get();
    if (ctx.dryRun()) return SyncResult.skipped(ENTITY, d.id(), "dry_run");

    Roles.grantScimManaged(ctx.realm(), user);

    Map<String, List<String>> attrs =
        user.getAttributes() == null
            ? new LinkedHashMap<>()
            : new LinkedHashMap<>(user.getAttributes());
    attrs.put(AttributeKeys.SCIM_DIRECTORY_USER_ID, List.of(d.id()));
    if (d.directoryId() != null)
      attrs.put(AttributeKeys.SCIM_DIRECTORY_ID_ATTR, List.of(d.directoryId()));
    if (d.idpId() != null) attrs.put(AttributeKeys.SCIM_IDP_ID, List.of(d.idpId()));
    if (d.state() != null) attrs.put(AttributeKeys.SCIM_STATE, List.of(d.state()));
    user.setAttributes(attrs);
    ctx.realm().users().get(user.getId()).update(user);

    return SyncResult.updated(ENTITY, d.id(), user.getId()).addNote("scim-managed role granted");
  }

  /** Used by webhook-driven flows where we already have the resolved KC user id. */
  public SyncResult applyToExisting(WDirectoryUser d, String keycloakUserId) {
    if (ctx.dryRun()) return SyncResult.skipped(ENTITY, d.id(), "dry_run");
    Roles.grantScimManaged(ctx.realm(), keycloakUserId);
    Map<String, String> attrs = new HashMap<>();
    attrs.put(AttributeKeys.SCIM_DIRECTORY_USER_ID, d.id());
    if (d.directoryId() != null) attrs.put(AttributeKeys.SCIM_DIRECTORY_ID_ATTR, d.directoryId());
    if (d.idpId() != null) attrs.put(AttributeKeys.SCIM_IDP_ID, d.idpId());
    if (d.state() != null) attrs.put(AttributeKeys.SCIM_STATE, d.state());
    // Re-fetch user, merge attrs
    UserRepresentation u = ctx.realm().users().get(keycloakUserId).toRepresentation();
    Map<String, List<String>> existing =
        u.getAttributes() == null ? new HashMap<>() : new HashMap<>(u.getAttributes());
    attrs.forEach((k, v) -> existing.put(k, List.of(v)));
    u.setAttributes(existing);
    ctx.realm().users().get(keycloakUserId).update(u);
    return SyncResult.updated(ENTITY, d.id(), keycloakUserId);
  }
}
