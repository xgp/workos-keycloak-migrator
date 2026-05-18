package io.phasetwo.migration.legacy;

import io.phasetwo.migration.common.AttributeKeys;
import io.phasetwo.migration.common.workos.WorkOSClient;
import io.phasetwo.migration.common.workos.model.Cursor;
import io.phasetwo.migration.common.workos.model.WDirectoryUser;
import io.phasetwo.migration.common.workos.model.WIdentity;
import io.phasetwo.migration.common.workos.model.WOrgMembership;
import io.phasetwo.migration.common.workos.model.WUser;
import io.phasetwo.service.model.OrganizationModel;
import io.phasetwo.service.model.OrganizationProvider;
import io.phasetwo.service.model.OrganizationRoleModel;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.FederatedIdentityModel;
import org.keycloak.models.IdentityProviderModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;

/**
 * Performs an in-process full-sync of a single user using only Keycloak SPIs + Phase Two's
 * {@link OrganizationProvider}. Mirrors what {@code UserSync}, {@code OrganizationMembershipSync}
 * and {@code DirectoryUserSync} in {@code common} do in the bulk migrator — minus the bits that
 * require the Keycloak/Phase Two admin clients (organisations are touched via the in-process
 * provider instead).
 */
@JBossLog
final class SessionUserSync {

    private final KeycloakSession session;
    private final RealmModel realm;
    private final WorkOSClient workos;

    SessionUserSync(KeycloakSession session, RealmModel realm, WorkOSClient workos) {
        this.session = session;
        this.realm = realm;
        this.workos = workos;
    }

    /**
     * Enrich the local user identified by {@code email}. Returns {@code true} when the user was
     * found and processed; {@code false} when no local user exists (the slow-migration import
     * didn't materialise it, so there is nothing to do).
     */
    boolean enrich(String email) {
        UserModel user = session.users().getUserByEmail(realm, email);
        if (user == null) {
            log.infof("post-commit sync: no local user for %s — skipping", email);
            return false;
        }
        Optional<WUser> wuOpt = workos.findUserByEmail(email);
        if (wuOpt.isEmpty()) {
            log.infof("post-commit sync: WorkOS has no user for %s — skipping", email);
            return false;
        }
        WUser wu = wuOpt.get();
        applyAttributes(user, wu);
        applyFederatedIdentities(user, wu);
        applyOrgMemberships(user, wu);
        applyScimManaged(user, email);
        log.infof("post-commit sync: enriched %s (workos.id=%s)", email, wu.id());
        return true;
    }

    private void applyAttributes(UserModel user, WUser wu) {
        setIfPresent(user, AttributeKeys.WORKOS_ID, wu.id());
        setIfPresent(user, AttributeKeys.WORKOS_EXTERNAL_ID, wu.externalId());
        setIfPresent(user, AttributeKeys.WORKOS_LOCALE, wu.locale());
        setIfPresent(user, AttributeKeys.WORKOS_PROFILE_PICTURE_URL, wu.profilePictureUrl());
        setIfPresent(user, AttributeKeys.WORKOS_LAST_SIGN_IN_AT, wu.lastSignInAt());
        if (wu.metadata() != null) {
            wu.metadata().forEach((k, v) -> user.setSingleAttribute(AttributeKeys.WORKOS_METADATA_PREFIX + k, v));
        }
        if (user.getFirstAttribute(AttributeKeys.WORKOS_MIGRATED_AT) == null) {
            user.setSingleAttribute(AttributeKeys.WORKOS_MIGRATED_AT, Instant.now().toString());
        }
        user.setSingleAttribute(AttributeKeys.WORKOS_LAST_SYNC_AT, Instant.now().toString());
    }

    private void applyFederatedIdentities(UserModel user, WUser wu) {
        java.util.List<WIdentity> identities;
        try {
            identities = workos.listUserIdentities(wu.id());
        } catch (Exception e) {
            log.debugf("could not list identities for %s: %s", wu.id(), e.toString());
            return;
        }
        for (WIdentity id : identities) {
            if (id == null || id.provider() == null || id.idpId() == null) continue;
            String alias = mapAlias(id.provider());
            // Skip if the realm doesn't have this IdP — admin can add later, then rerun the bulk
            // migrator (we don't auto-create stub IdPs from this entry point because the user
            // explicitly hits us via the slow-migration flow and full bulk runs are expected to
            // have already handled IdP provisioning).
            IdentityProviderModel idp = realm.getIdentityProviderByAlias(alias);
            if (idp == null) {
                log.debugf("no IdP %s in realm — skipping federated identity for %s", alias, wu.email());
                continue;
            }
            FederatedIdentityModel existing = session.users().getFederatedIdentity(realm, user, alias);
            FederatedIdentityModel desired = new FederatedIdentityModel(alias, id.idpId(), wu.email());
            if (existing == null) {
                session.users().addFederatedIdentity(realm, user, desired);
            } else if (!Objects.equals(existing.getUserId(), desired.getUserId())) {
                session.users().updateFederatedIdentity(realm, user, desired);
            }
        }
    }

    private void applyOrgMemberships(UserModel user, WUser wu) {
        OrganizationProvider orgs = session.getProvider(OrganizationProvider.class);
        if (orgs == null) {
            log.warn("Phase Two OrganizationProvider not available; skipping org membership sync");
            return;
        }
        java.util.List<WOrgMembership> userMemberships = listMembershipsForUser(wu.id());
        for (WOrgMembership m : userMemberships) {
            OrganizationModel org = findPtOrgByWorkosId(orgs, m.organizationId());
            if (org == null) {
                log.debugf("PT org for WorkOS %s not migrated yet; skipping membership", m.organizationId());
                continue;
            }
            if (!org.hasMembership(user)) {
                org.grantMembership(user);
                user.setSingleAttribute(AttributeKeys.WORKOS_ORG_MEMBERSHIP_ID, m.id());
                log.infof("post-commit sync: granted %s membership in %s", user.getUsername(), org.getName());
            }
            grantOrgRole(org, user, m.role() == null ? null : m.role().slug());
            if (m.roleAssignments() != null) {
                for (WOrgMembership.RoleAssignment ra : m.roleAssignments()) {
                    if (ra.role() != null) grantOrgRole(org, user, ra.role().slug());
                }
            }
            if (Boolean.TRUE.equals(m.directoryManaged())) {
                grantScimManagedRole(user);
            }
        }
    }

    private void applyScimManaged(UserModel user, String email) {
        Optional<WDirectoryUser> du;
        try {
            du = workos.findDirectoryUserByEmail(email);
        } catch (Exception e) {
            // WorkOS doesn't always accept email as a top-level filter on /directory_users
            // (it scopes to a specific directory). The scim-managed role is also granted via
            // the org-membership branch when directory_managed=true, so this is a best-effort
            // path — fall back silently.
            log.debugf("directory_user lookup by email failed for %s: %s", email, e.toString());
            return;
        }
        if (du.isEmpty()) return;
        grantScimManagedRole(user);
        WDirectoryUser d = du.get();
        if (d.directoryId() != null) user.setSingleAttribute(AttributeKeys.SCIM_DIRECTORY_ID_ATTR, d.directoryId());
        if (d.id() != null) user.setSingleAttribute(AttributeKeys.SCIM_DIRECTORY_USER_ID, d.id());
        if (d.idpId() != null) user.setSingleAttribute(AttributeKeys.SCIM_IDP_ID, d.idpId());
        if (d.state() != null) user.setSingleAttribute(AttributeKeys.SCIM_STATE, d.state());
    }

    private void grantScimManagedRole(UserModel user) {
        RoleModel role = realm.getRole(AttributeKeys.SCIM_MANAGED_ROLE);
        if (role == null) {
            role = realm.addRole(AttributeKeys.SCIM_MANAGED_ROLE);
            role.setDescription("WorkOS Directory Sync managed user — auto-applied by the migrator");
        }
        if (!user.hasRole(role)) user.grantRole(role);
    }

    private OrganizationModel findPtOrgByWorkosId(OrganizationProvider orgs, String workosOrgId) {
        Map<String, String> search = Map.of(AttributeKeys.WORKOS_ID, workosOrgId);
        return orgs.searchForOrganizationByAttributesStream(realm, search, 0, 1)
                .findFirst()
                .orElse(null);
    }

    private void grantOrgRole(OrganizationModel org, UserModel user, String slug) {
        if (slug == null || slug.isBlank()) return;
        OrganizationRoleModel role = org.getRoleByName(slug);
        if (role == null) return; // role wasn't migrated yet; bulk run will fix
        role.grantRole(user);
    }

    /** Calls /user_management/organization_memberships?user_id=… via the underlying WorkOS client. */
    private java.util.List<WOrgMembership> listMembershipsForUser(String userId) {
        // The typed client takes organization_id, not user_id. Use the more general listing by
        // walking each org we know about and matching by user_id is expensive. Phase Two doesn't
        // give us that lookup either. As a pragmatic compromise we ask WorkOS for membership
        // by-user via a fresh paged call.
        java.util.List<WOrgMembership> all = new java.util.ArrayList<>();
        Cursor cursor = Cursor.empty();
        while (true) {
            var page = workos.listOrganizationMembershipsForUser(userId, cursor, 100);
            all.addAll(page.data());
            String next = page.listMetadata() == null ? null : page.listMetadata().after();
            if (next == null || next.isEmpty()) break;
            cursor = new Cursor(null, next);
        }
        return all;
    }

    private static void setIfPresent(UserModel user, String key, String value) {
        if (value != null && !value.isBlank()) user.setSingleAttribute(key, value);
    }

    private static String mapAlias(String provider) {
        if (provider == null) return "oauth-unknown";
        return "oauth-" + provider.toLowerCase(Locale.ROOT).replace("oauth", "");
    }
}
