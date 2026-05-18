package io.phasetwo.wkm.common.sync;

import io.phasetwo.client.OrganizationResource;
import io.phasetwo.client.openapi.model.OrganizationRepresentation;
import io.phasetwo.wkm.common.keycloak.Lookups;
import io.phasetwo.wkm.common.workos.model.WOrgMembership;
import java.util.Optional;
import org.keycloak.representations.idm.UserRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class OrganizationMembershipSync implements EntitySync<WOrgMembership> {

    private static final Logger log = LoggerFactory.getLogger(OrganizationMembershipSync.class);
    private static final String ENTITY = "organization_membership";

    private final SyncContext ctx;

    public OrganizationMembershipSync(SyncContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public SyncResult sync(WOrgMembership m) {
        if (m == null || m.userId() == null || m.organizationId() == null) {
            return SyncResult.failed(ENTITY, m == null ? null : m.id(), "missing user or org id");
        }

        Optional<UserRepresentation> user = Lookups.userByWorkosId(ctx.realm(), m.userId());
        if (user.isEmpty()) {
            return SyncResult.skipped(ENTITY, m.id(), "user_not_yet_migrated:" + m.userId());
        }
        Optional<OrganizationRepresentation> org =
                Lookups.orgByWorkosId(ctx.phaseTwo(), ctx.realmName(), m.organizationId());
        if (org.isEmpty()) {
            return SyncResult.skipped(ENTITY, m.id(), "org_not_yet_migrated:" + m.organizationId());
        }

        if (ctx.dryRun()) return SyncResult.skipped(ENTITY, m.id(), "dry_run");

        OrganizationResource orgRes = ctx.phaseTwo().organizations(ctx.realmName())
                .organization(org.get().getId());
        boolean already = false;
        try {
            already = orgRes.memberships().isMember(user.get().getId());
        } catch (Exception e) {
            log.debug("isMember check failed: {}", e.toString());
        }
        if (!already) {
            orgRes.memberships().add(user.get().getId());
        }

        // Grant roles
        if (m.role() != null && m.role().slug() != null) {
            grantRole(orgRes, user.get().getId(), m.role().slug());
        }
        if (m.roleAssignments() != null) {
            for (WOrgMembership.RoleAssignment ra : m.roleAssignments()) {
                if (ra.role() != null && ra.role().slug() != null) {
                    grantRole(orgRes, user.get().getId(), ra.role().slug());
                }
            }
        }

        return already
                ? SyncResult.updated(ENTITY, m.id(), org.get().getId() + ":" + user.get().getId())
                : SyncResult.created(ENTITY, m.id(), org.get().getId() + ":" + user.get().getId());
    }

    private void grantRole(OrganizationResource orgRes, String userId, String roleSlug) {
        try {
            orgRes.roles().grant(roleSlug, userId);
        } catch (Exception e) {
            log.debug("grantRole({},{}) failed: {}", roleSlug, userId, e.toString());
        }
    }
}
