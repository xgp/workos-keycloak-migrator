package io.phasetwo.migration.common.webhook;

import com.fasterxml.jackson.databind.JsonNode;
import io.phasetwo.migration.common.sync.DirectoryGroupSync;
import io.phasetwo.migration.common.sync.DirectorySync;
import io.phasetwo.migration.common.sync.IdentityProviderSync;
import io.phasetwo.migration.common.sync.OrganizationMembershipSync;
import io.phasetwo.migration.common.sync.OrganizationSync;
import io.phasetwo.migration.common.sync.RoleSync;
import io.phasetwo.migration.common.sync.SyncContext;
import io.phasetwo.migration.common.sync.SyncResult;
import io.phasetwo.migration.common.sync.UserSync;
import io.phasetwo.migration.common.util.JsonUtil;
import io.phasetwo.migration.common.workos.model.WConnection;
import io.phasetwo.migration.common.workos.model.WDirectory;
import io.phasetwo.migration.common.workos.model.WDirectoryGroup;
import io.phasetwo.migration.common.workos.model.WOrgMembership;
import io.phasetwo.migration.common.workos.model.WOrganization;
import io.phasetwo.migration.common.workos.model.WRole;
import io.phasetwo.migration.common.workos.model.WUser;
import io.phasetwo.migration.common.workos.model.WebhookEvent;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Dispatches a {@link WebhookEvent} to the matching synchroniser. */
public class WebhookEventRouter {

    private static final Logger log = LoggerFactory.getLogger(WebhookEventRouter.class);

    private final SyncContext ctx;
    private final String serverUrl;

    public WebhookEventRouter(SyncContext ctx, String serverUrl) {
        this.ctx = ctx;
        this.serverUrl = serverUrl;
    }

    public Optional<SyncResult> dispatch(WebhookEvent ev) {
        if (ev == null || ev.event() == null) return Optional.empty();
        String type = ev.event();
        JsonNode data = ev.data();
        log.debug("dispatching webhook event {} id={}", type, ev.id());
        try {
            return switch (type) {
                case "user.created", "user.updated" ->
                        Optional.of(new UserSync(ctx).sync(convert(data, WUser.class)));
                case "user.deleted" -> Optional.of(deleteUser(data));
                case "organization.created", "organization.updated" ->
                        Optional.of(new OrganizationSync(ctx).sync(convert(data, WOrganization.class)));
                case "organization.deleted" -> Optional.of(deleteOrg(data));
                case "organization_membership.created",
                        "organization_membership.updated" ->
                        Optional.of(new OrganizationMembershipSync(ctx).sync(convert(data, WOrgMembership.class)));
                case "organization_membership.deleted" -> Optional.of(removeMembership(data));
                case "role.created", "role.updated" ->
                        Optional.of(new RoleSync(ctx).sync(convert(data, WRole.class)));
                case "organization_role.created", "organization_role.updated" ->
                        Optional.of(syncOrgRole(data));
                case "connection.activated",
                        "connection.deactivated",
                        "connection.saml_certificate_renewed" ->
                        Optional.of(new IdentityProviderSync(ctx).sync(convert(data, WConnection.class)));
                case "connection.deleted" -> Optional.of(deleteIdp(data));
                case "dsync.activated", "dsync.deleted" ->
                        Optional.of(new DirectorySync(ctx, serverUrl).sync(convert(data, WDirectory.class)));
                case "dsync.user.created", "dsync.user.updated" ->
                        Optional.of(syncDirectoryUser(data));
                case "dsync.user.deleted" ->
                        Optional.of(new UserSync(ctx).sync(convert(data, WUser.class)));
                case "dsync.group.created",
                        "dsync.group.updated" ->
                        Optional.of(new DirectoryGroupSync(ctx).sync(convert(data, WDirectoryGroup.class)));
                case "organization_domain.created",
                        "organization_domain.updated",
                        "organization_domain.verified",
                        "organization_domain.deleted" ->
                        // domain events are handled by re-syncing the parent org if WorkOS supplies organization_id
                        Optional.of(resyncParentOrg(data));
                default -> Optional.empty();
            };
        } catch (Exception e) {
            log.error("dispatch failed for {}: {}", type, e.toString());
            throw e;
        }
    }

    private <T> T convert(JsonNode data, Class<T> cls) {
        return JsonUtil.mapper().convertValue(data, cls);
    }

    private SyncResult deleteUser(JsonNode data) {
        String id = data == null ? null : textOr(data, "id");
        if (id == null) return SyncResult.failed("user", null, "missing id");
        return io.phasetwo.migration.common.keycloak.Lookups.userByWorkosId(ctx.realm(), id)
                .map(u -> {
                    ctx.realm().users().delete(u.getId());
                    return SyncResult.deleted("user", id, u.getId());
                })
                .orElseGet(() -> SyncResult.skipped("user", id, "not_found"));
    }

    private SyncResult deleteOrg(JsonNode data) {
        String id = data == null ? null : textOr(data, "id");
        if (id == null) return SyncResult.failed("organization", null, "missing id");
        return io.phasetwo.migration.common.keycloak.Lookups.orgByWorkosId(ctx.phaseTwo(), ctx.realmName(), id)
                .map(o -> {
                    ctx.phaseTwo().organizations(ctx.realmName()).organization(o.getId()).delete();
                    return SyncResult.deleted("organization", id, o.getId());
                })
                .orElseGet(() -> SyncResult.skipped("organization", id, "not_found"));
    }

    private SyncResult removeMembership(JsonNode data) {
        if (data == null) return SyncResult.failed("organization_membership", null, "no data");
        String userId = textOr(data, "user_id");
        String orgId = textOr(data, "organization_id");
        if (userId == null || orgId == null) {
            return SyncResult.failed("organization_membership", null, "missing user/org id");
        }
        var user = io.phasetwo.migration.common.keycloak.Lookups.userByWorkosId(ctx.realm(), userId);
        var org = io.phasetwo.migration.common.keycloak.Lookups.orgByWorkosId(ctx.phaseTwo(), ctx.realmName(), orgId);
        if (user.isEmpty() || org.isEmpty()) {
            return SyncResult.skipped("organization_membership", null, "not_found");
        }
        ctx.phaseTwo().organizations(ctx.realmName()).organization(org.get().getId()).memberships().remove(user.get().getId());
        return SyncResult.deleted("organization_membership", null, org.get().getId() + ":" + user.get().getId());
    }

    private SyncResult deleteIdp(JsonNode data) {
        String id = textOr(data, "id");
        if (id == null) return SyncResult.failed("identity_provider", null, "missing id");
        String alias = "workos-" + id;
        try {
            ctx.realm().identityProviders().get(alias).remove();
            return SyncResult.deleted("identity_provider", id, alias);
        } catch (jakarta.ws.rs.NotFoundException e) {
            return SyncResult.skipped("identity_provider", id, "not_found");
        }
    }

    private SyncResult resyncParentOrg(JsonNode data) {
        if (data == null) return SyncResult.skipped("organization_domain", null, "no_data");
        String orgId = textOr(data, "organization_id");
        if (orgId == null) return SyncResult.skipped("organization_domain", null, "no_org_id");
        return ctx.workos().getOrganization(orgId)
                .map(o -> new OrganizationSync(ctx).sync(o))
                .orElseGet(() -> SyncResult.skipped("organization_domain", null, "org_not_in_workos"));
    }

    private SyncResult syncDirectoryUser(JsonNode data) {
        // Treat the dsync.user payload as a WorkOS DirectoryUser so we route through the
        // SCIM-tagging path. UserSync runs first via findUserByEmail; if no KC user exists yet,
        // DirectoryUserSync defers and a follow-up bulk run will reconcile.
        io.phasetwo.migration.common.workos.model.WDirectoryUser du = convert(
                data, io.phasetwo.migration.common.workos.model.WDirectoryUser.class);
        return new io.phasetwo.migration.common.sync.DirectoryUserSync(ctx).sync(du);
    }

    private SyncResult syncOrgRole(JsonNode data) {
        WRole role = convert(data, WRole.class);
        String orgId = textOr(data, "organization_id");
        if (orgId == null && role != null && role.resourceTypeSlug() != null) orgId = textOr(data, "resource_id");
        if (orgId == null) return SyncResult.failed("organization_role", role == null ? null : role.id(), "missing org id");
        return new RoleSync(ctx).syncOrganizationRole(role, orgId);
    }

    private static String textOr(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
