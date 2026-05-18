package io.phasetwo.migration.webhook;

import lombok.extern.jbosslog.JBossLog;
import com.fasterxml.jackson.databind.JsonNode;
import io.phasetwo.migration.common.AttributeKeys;
import io.phasetwo.migration.common.util.JsonUtil;
import io.phasetwo.migration.common.webhook.WebhookVerifier;
import io.phasetwo.migration.common.workos.model.WebhookEvent;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.List;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
/**
 * Receives WorkOS webhooks at {@code /realms/{realm}/workos-webhook/{publicId}}, verifies the
 * signature, and applies in-process mutations where it can. For events that require the full
 * Phase Two / Keycloak admin-client surface (organization create, idp upsert, SCIM stubs), the
 * resource marks the realm as needing reconciliation and the next bulk migrator run picks them
 * up via the cursor + state mechanism in {@code common}. This trade-off avoids dragging the
 * Keycloak admin client into the Keycloak server's own classpath.
 */
@JBossLog
public class WorkOSWebhookResource {
    static final String PENDING_RESYNC = AttributeKeys.MIGRATION_PREFIX + "pending_resync";
    static final String LAST_EVENT_AT = AttributeKeys.MIGRATION_PREFIX + "webhook.last_event_at";

    private final KeycloakSession session;
    private final long eventToleranceMillis;

    public WorkOSWebhookResource(KeycloakSession session, long eventToleranceMillis) {
        this.session = session;
        this.eventToleranceMillis = eventToleranceMillis;
    }

    @POST
    @Path("{publicId}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Response receive(
            @PathParam("publicId") String publicId,
            @HeaderParam("WorkOS-Signature") String signature,
            byte[] body) {

        RealmModel realm = session.getContext().getRealm();
        if (realm == null) return Response.status(404).build();
        String expectedPublicId = realm.getAttribute(AttributeKeys.REALM_WEBHOOK_PUBLIC_ID);
        if (expectedPublicId == null || !expectedPublicId.equals(publicId)) {
            log.debugf("realm %s: unknown public webhook id %s", realm.getName(), publicId);
            return Response.status(404).build();
        }
        String secret = realm.getAttribute(AttributeKeys.REALM_WEBHOOK_SECRET);
        if (secret == null) {
            log.warnf("realm %s: webhook secret not configured", realm.getName());
            return Response.status(503).build();
        }
        WebhookVerifier verifier = new WebhookVerifier(eventToleranceMillis);
        if (!verifier.verify(signature, body, secret)) {
            log.warnf("realm %s: rejecting webhook with bad signature", realm.getName());
            return Response.status(401).build();
        }

        WebhookEvent event;
        try {
            JsonNode root = JsonUtil.mapper().readTree(body);
            event = JsonUtil.mapper().treeToValue(root, WebhookEvent.class);
        } catch (Exception e) {
            log.warnf("realm %s: malformed webhook body: %s", realm.getName(), e.toString());
            return Response.status(400).build();
        }

        realm.setAttribute(LAST_EVENT_AT, Instant.now().toString());
        try {
            dispatch(realm, event);
            return Response.ok("{\"ok\":true}", MediaType.APPLICATION_JSON).build();
        } catch (Exception e) {
            log.errorf(e, "realm %s: webhook dispatch failed: %s", realm.getName(), e.toString());
            // 500 makes WorkOS retry the delivery.
            return Response.status(500).build();
        }
    }

    private void dispatch(RealmModel realm, WebhookEvent event) {
        if (event == null || event.event() == null) return;
        String type = event.event();
        JsonNode data = event.data();
        switch (type) {
            case "user.deleted" -> handleUserDeleted(realm, data);
            case "user.updated", "user.created", "dsync.user.updated", "dsync.user.created" ->
                    handleUserUpsert(realm, data);
            case "dsync.user.deleted" -> handleUserDeleted(realm, data);
            case "organization_membership.deleted" -> handleMembershipDeleted(realm, data);
            default -> log.infof("realm %s: webhook %s accepted — marked for bulk resync", realm.getName(), type);
        }
        markPendingResync(realm, type);
    }

    private void handleUserDeleted(RealmModel realm, JsonNode data) {
        String workosId = textOr(data, "id");
        if (workosId == null) return;
        UserModel u = findByWorkosId(realm, workosId);
        if (u != null) {
            session.users().removeUser(realm, u);
            log.infof("realm %s: removed user %s via webhook (workos id %s)", realm.getName(), u.getUsername(), workosId);
        }
    }

    private void handleUserUpsert(RealmModel realm, JsonNode data) {
        String workosId = textOr(data, "id");
        String email = textOr(data, "email");
        if (workosId == null || email == null) return;
        UserModel u = findByWorkosId(realm, workosId);
        if (u == null) u = session.users().getUserByEmail(realm, email);
        if (u == null) {
            // Defer creation to the bulk migrator so org-/idp- linkage flows cleanly.
            log.infof("realm %s: webhook user.created/updated for %s deferred to next bulk run", realm.getName(), email);
            return;
        }
        u.setSingleAttribute(AttributeKeys.WORKOS_ID, workosId);
        u.setEmail(email);
        String first = textOr(data, "first_name");
        String last = textOr(data, "last_name");
        if (first != null) u.setFirstName(first);
        if (last != null) u.setLastName(last);
        Boolean ev = boolOr(data, "email_verified");
        if (ev != null) u.setEmailVerified(ev);
        String externalId = textOr(data, "external_id");
        if (externalId != null) u.setSingleAttribute(AttributeKeys.WORKOS_EXTERNAL_ID, externalId);
        u.setSingleAttribute(AttributeKeys.WORKOS_LAST_SYNC_AT, Instant.now().toString());
        log.infof("realm %s: updated user %s via webhook (workos id %s)", realm.getName(), email, workosId);
    }

    private void handleMembershipDeleted(RealmModel realm, JsonNode data) {
        // Without the Phase Two admin client on the extension classpath we can't unlink an org
        // member here; defer to bulk reconcile.
        log.infof("realm %s: organization_membership.deleted deferred to next bulk run (%s)", realm.getName(), textOr(data, "id"));
    }

    private void markPendingResync(RealmModel realm, String eventType) {
        realm.setAttribute(PENDING_RESYNC, "true");
        realm.setAttribute(AttributeKeys.MIGRATION_PREFIX + "webhook.last_event_type", eventType);
    }

    private UserModel findByWorkosId(RealmModel realm, String workosId) {
        List<UserModel> matches = session.users()
                .searchForUserByUserAttributeStream(realm, AttributeKeys.WORKOS_ID, workosId)
                .limit(1)
                .toList();
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static String textOr(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static Boolean boolOr(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asBoolean();
    }
}
