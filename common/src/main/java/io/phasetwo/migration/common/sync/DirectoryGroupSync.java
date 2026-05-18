package io.phasetwo.migration.common.sync;

import io.phasetwo.client.openapi.model.OrganizationRepresentation;
import io.phasetwo.migration.common.keycloak.Lookups;
import io.phasetwo.migration.common.workos.model.WDirectoryGroup;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Optional;
import org.keycloak.admin.client.resource.GroupsResource;
import org.keycloak.representations.idm.GroupRepresentation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Materialises WorkOS Directory Groups as Keycloak groups under a per-organization parent
 * {@code /org-{pt_org_id}} — the parent name embeds the Phase Two organization id (not the
 * WorkOS id) so it stays stable for admins navigating the KC group tree alongside the PT org UI.
 */
public class DirectoryGroupSync implements EntitySync<WDirectoryGroup> {

    private static final Logger log = LoggerFactory.getLogger(DirectoryGroupSync.class);
    private static final String ENTITY = "directory_group";

    private final SyncContext ctx;

    public DirectoryGroupSync(SyncContext ctx) {
        this.ctx = ctx;
    }

    @Override
    public SyncResult sync(WDirectoryGroup g) {
        if (g == null || g.id() == null || g.organizationId() == null || g.name() == null) {
            return SyncResult.failed(ENTITY, g == null ? null : g.id(), "missing fields");
        }
        Optional<OrganizationRepresentation> ptOrg =
                Lookups.orgByWorkosId(ctx.phaseTwo(), ctx.realmName(), g.organizationId());
        if (ptOrg.isEmpty()) {
            return SyncResult.skipped(ENTITY, g.id(), "org_not_yet_migrated:" + g.organizationId());
        }
        String parentName = "org-" + ptOrg.get().getId();
        GroupsResource groups = ctx.realm().groups();
        if (ctx.dryRun()) return SyncResult.skipped(ENTITY, g.id(), "dry_run");

        GroupRepresentation parent = findOrCreateTopLevel(groups, parentName);
        // Look for an existing child by name
        List<GroupRepresentation> children = parent.getSubGroups() == null ? List.of() : parent.getSubGroups();
        for (GroupRepresentation child : children) {
            if (g.name().equals(child.getName())) {
                return SyncResult.skipped(ENTITY, g.id(), "exists");
            }
        }
        GroupRepresentation child = new GroupRepresentation();
        child.setName(g.name());
        try (Response resp = ctx.realm().groups().group(parent.getId()).subGroup(child)) {
            int code = resp.getStatus();
            if (code == 201) return SyncResult.created(ENTITY, g.id(), parentName + "/" + g.name());
            return SyncResult.failed(ENTITY, g.id(), "create returned " + code);
        } catch (Exception e) {
            log.warn("subgroup create failed: {}", e.toString());
            return SyncResult.failed(ENTITY, g.id(), e.toString());
        }
    }

    private GroupRepresentation findOrCreateTopLevel(GroupsResource groups, String name) {
        for (GroupRepresentation g : groups.groups(name, 0, 50, false)) {
            if (name.equals(g.getName())) {
                try {
                    return groups.group(g.getId()).toRepresentation();
                } catch (NotFoundException nfe) {
                    // race; fall through to create
                }
            }
        }
        GroupRepresentation rep = new GroupRepresentation();
        rep.setName(name);
        try (Response resp = groups.add(rep)) {
            String location = resp.getHeaderString("Location");
            String id = location == null ? null : location.substring(location.lastIndexOf('/') + 1);
            return groups.group(id).toRepresentation();
        }
    }
}
