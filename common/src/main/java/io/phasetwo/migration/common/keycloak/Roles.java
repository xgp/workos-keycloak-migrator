package io.phasetwo.migration.common.keycloak;

import lombok.extern.jbosslog.JBossLog;
import io.phasetwo.migration.common.AttributeKeys;
import java.util.List;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
/** Helpers for the realm-level roles the migrator manages. */
@JBossLog
public final class Roles {
    private Roles() {}

    /**
     * Ensure the {@value AttributeKeys#SCIM_MANAGED_ROLE} realm role exists. Idempotent — if a
     * role with the same name already exists we leave it alone.
     */
    public static void ensureScimManagedRole(RealmResource realm) {
        try {
            realm.roles().get(AttributeKeys.SCIM_MANAGED_ROLE).toRepresentation();
            return;
        } catch (jakarta.ws.rs.NotFoundException ignored) {
            // create below
        } catch (Exception e) {
            log.debugf("scim-managed lookup failed; attempting create: %s", e.toString());
        }
        RoleRepresentation r = new RoleRepresentation();
        r.setName(AttributeKeys.SCIM_MANAGED_ROLE);
        r.setDescription("WorkOS Directory Sync managed user — auto-applied by the migrator");
        r.setComposite(false);
        try {
            realm.roles().create(r);
            log.infof("created realm role %s", AttributeKeys.SCIM_MANAGED_ROLE);
        } catch (Exception e) {
            log.warnf("could not create realm role %s: %s", AttributeKeys.SCIM_MANAGED_ROLE, e.toString());
        }
    }

    /** Grant the scim-managed role to {@code userId} (idempotent). */
    public static void grantScimManaged(RealmResource realm, String userId) {
        RoleRepresentation r;
        try {
            r = realm.roles().get(AttributeKeys.SCIM_MANAGED_ROLE).toRepresentation();
        } catch (jakarta.ws.rs.NotFoundException nfe) {
            ensureScimManagedRole(realm);
            r = realm.roles().get(AttributeKeys.SCIM_MANAGED_ROLE).toRepresentation();
        }
        List<RoleRepresentation> existing = realm.users().get(userId).roles().realmLevel().listAll();
        for (RoleRepresentation hit : existing) {
            if (AttributeKeys.SCIM_MANAGED_ROLE.equals(hit.getName())) return;
        }
        realm.users().get(userId).roles().realmLevel().add(List.of(r));
    }

    /** Convenience overload accepting a {@link UserRepresentation}. */
    public static void grantScimManaged(RealmResource realm, UserRepresentation user) {
        grantScimManaged(realm, user.getId());
    }
}
