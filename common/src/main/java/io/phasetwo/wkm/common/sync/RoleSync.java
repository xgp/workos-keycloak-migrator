package io.phasetwo.wkm.common.sync;

import io.phasetwo.client.OrganizationRolesResource;
import io.phasetwo.client.openapi.model.OrganizationRepresentation;
import io.phasetwo.client.openapi.model.OrganizationRoleRepresentation;
import io.phasetwo.wkm.common.AttributeKeys;
import io.phasetwo.wkm.common.keycloak.Lookups;
import io.phasetwo.wkm.common.workos.model.WRole;
import java.util.*;
import org.keycloak.admin.client.resource.RoleResource;
import org.keycloak.representations.idm.RoleRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RoleSync implements EntitySync<WRole> {

    private static final Logger log = LoggerFactory.getLogger(RoleSync.class);
    private static final String ENTITY = "role";

    private final SyncContext ctx;

    public RoleSync(SyncContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public SyncResult sync(WRole r) {
        if (r == null || r.slug() == null) {
            return SyncResult.failed(ENTITY, r == null ? null : r.id(), "missing slug");
        }
        if (r.isEnvironmentRole()) return syncRealmRole(r);
        if (r.isOrganizationRole()) {
            // OrganizationRole is scoped to an org but the listEnvironmentRoles call won't return
            // them — listOrganizationRoles(orgId) is used per org. Callers must invoke this method
            // with a per-org WRole; we honour the OrganizationRole branch in case it shows up.
            return SyncResult.skipped(ENTITY, r.id(), "use_syncOrganizationRole_with_org_context");
        }
        return SyncResult.skipped(ENTITY, r.id(), "unknown_role_type:" + r.type());
    }

    private SyncResult syncRealmRole(WRole r) {
        boolean exists;
        RoleRepresentation rep;
        try {
            rep = ctx.realm().roles().get(r.slug()).toRepresentation();
            exists = rep != null;
        } catch (jakarta.ws.rs.NotFoundException e) {
            exists = false;
            rep = new RoleRepresentation();
        }

        rep.setName(r.slug());
        rep.setDescription(r.description());
        rep.setComposite(false);
        Map<String, List<String>> attrs = rep.getAttributes() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(rep.getAttributes());
        attrs.put(AttributeKeys.WORKOS_ID, List.of(r.id()));
        attrs.put(AttributeKeys.WORKOS_ROLE_SLUG, List.of(r.slug()));
        if (r.permissions() != null && !r.permissions().isEmpty()) {
            attrs.put(AttributeKeys.WORKOS_ROLE_PERMISSIONS, List.of(String.join(",", r.permissions())));
        }
        rep.setAttributes(attrs);

        if (ctx.dryRun()) return SyncResult.skipped(ENTITY, r.id(), "dry_run");

        if (exists) {
            ctx.realm().roles().get(r.slug()).update(rep);
            return SyncResult.updated(ENTITY, r.id(), r.slug());
        }
        ctx.realm().roles().create(rep);
        return SyncResult.created(ENTITY, r.id(), r.slug());
    }

    /**
     * Per-organization role sync. The {@code organizationId} is the WorkOS organization id; this
     * method resolves the PT org via the workos.id attribute.
     */
    public SyncResult syncOrganizationRole(WRole r, String workosOrgId) {
        if (r == null || r.slug() == null) {
            return SyncResult.failed("organization_role", r == null ? null : r.id(), "missing slug");
        }
        Optional<OrganizationRepresentation> org = Lookups.orgByWorkosId(ctx.phaseTwo(), ctx.realmName(), workosOrgId);
        if (org.isEmpty()) {
            return SyncResult.skipped("organization_role", r.id(), "org_not_yet_migrated:" + workosOrgId);
        }
        OrganizationRolesResource roles = ctx.phaseTwo().organizations(ctx.realmName())
                .organization(org.get().getId()).roles();

        String permString = r.permissions() == null || r.permissions().isEmpty()
                ? "" : "[permissions:" + String.join(",", r.permissions()) + "] ";
        String description = (permString + (r.description() == null ? "" : r.description())
                + " [wos:" + r.id() + "]").trim();

        OrganizationRoleRepresentation existing = null;
        try {
            existing = roles.get(r.slug());
        } catch (jakarta.ws.rs.NotFoundException ignored) {
        } catch (Exception e) {
            log.debug("PT role get({}) failed: {}", r.slug(), e.toString());
        }

        OrganizationRoleRepresentation rep = existing != null ? existing : new OrganizationRoleRepresentation();
        rep.setName(r.slug());
        rep.setDescription(description);

        if (ctx.dryRun()) return SyncResult.skipped("organization_role", r.id(), "dry_run");

        if (existing != null) {
            roles.update(r.slug(), rep);
            return SyncResult.updated("organization_role", r.id(), r.slug());
        }
        roles.create(rep);
        return SyncResult.created("organization_role", r.id(), r.slug());
    }

    /** Used by tests / unused. */
    @SuppressWarnings("unused")
    private RoleResource roleResource(String name) {
        return ctx.realm().roles().get(name);
    }
}
