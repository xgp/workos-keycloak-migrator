package io.phasetwo.migration.legacy;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.phasetwo.migration.common.AttributeKeys;
import io.phasetwo.migration.common.util.JsonUtil;
import io.phasetwo.migration.common.workos.WorkOSHttpClient;
import io.phasetwo.migration.common.workos.model.WIdentity;
import io.phasetwo.migration.common.workos.model.WUser;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;

/** keycloak-user-migration legacy-service implementation. */
@JBossLog
public class WorkOSLegacyResource {
  private final KeycloakSession session;

  public WorkOSLegacyResource(KeycloakSession session) {
    this.session = session;
  }

  @GET
  @Path("{username}")
  @Produces(MediaType.APPLICATION_JSON)
  public Response getUser(
      @PathParam("username") String username, @HeaderParam("Authorization") String authorization) {

    RealmModel realm = session.getContext().getRealm();
    if (!authorize(realm, authorization)) return Response.status(401).build();

    WorkOSHttpClient w = client(realm);
    if (w == null) return Response.status(503).build();
    Optional<WUser> user = w.findUserByEmail(username);
    if (user.isEmpty()) return Response.status(404).build();
    WUser u = user.get();

    List<WIdentity> identities;
    try {
      identities = w.listUserIdentities(u.id());
    } catch (Exception e) {
      identities = List.of();
    }

    ObjectNode out = JsonUtil.mapper().createObjectNode();
    out.put("username", u.email());
    out.put("email", u.email());
    if (u.firstName() != null) out.put("firstName", u.firstName());
    if (u.lastName() != null) out.put("lastName", u.lastName());
    out.put("enabled", true);
    out.put("emailVerified", Boolean.TRUE.equals(u.emailVerified()));

    ObjectNode attrs = JsonUtil.mapper().createObjectNode();
    attrs.set(AttributeKeys.WORKOS_ID, arr(u.id()));
    if (u.externalId() != null) attrs.set(AttributeKeys.WORKOS_EXTERNAL_ID, arr(u.externalId()));
    if (u.locale() != null) attrs.set(AttributeKeys.WORKOS_LOCALE, arr(u.locale()));
    if (u.profilePictureUrl() != null)
      attrs.set(AttributeKeys.WORKOS_PROFILE_PICTURE_URL, arr(u.profilePictureUrl()));
    if (u.lastSignInAt() != null)
      attrs.set(AttributeKeys.WORKOS_LAST_SIGN_IN_AT, arr(u.lastSignInAt()));
    if (u.metadata() != null) {
      for (Map.Entry<String, String> e : u.metadata().entrySet()) {
        attrs.set(AttributeKeys.WORKOS_METADATA_PREFIX + e.getKey(), arr(e.getValue()));
      }
    }
    if (!identities.isEmpty()) {
      // not part of the keycloak-user-migration schema strictly, but useful as an attribute
      ArrayNode idArr = JsonUtil.mapper().createArrayNode();
      for (WIdentity id : identities) idArr.add(id.provider() + ":" + id.idpId());
      attrs.set("workos.identities", idArr);
    }
    out.set("attributes", attrs);
    out.set("roles", JsonUtil.mapper().createArrayNode());
    out.set("groups", JsonUtil.mapper().createArrayNode());
    out.set("requiredActions", JsonUtil.mapper().createArrayNode());
    // Per SPEC: omit "organizations" (refers to native KC orgs, not Phase Two)

    // Schedule a full sync of the user (attributes, federated identities, org memberships
    // and roles, scim-managed tagging) to run after the request transaction commits — by
    // that time Keycloak's user-federation provider has imported the local user record so
    // we have something to enrich.
    scheduleAfterCommitEnrich(realm, u.email());

    return Response.ok(out).build();
  }

  private void scheduleAfterCommitEnrich(RealmModel realm, String email) {
    String apiKey = realm.getAttribute("workos.migration.api_key");
    if (apiKey == null) return;
    String apiBase =
        Optional.ofNullable(realm.getAttribute("workos.migration.api_base_url"))
            .orElse("https://api.workos.com");
    EnrichUserTransaction tx =
        new EnrichUserTransaction(session, realm.getName(), email, apiBase, apiKey);
    session.getTransactionManager().enlistAfterCompletion(tx);
  }

  @POST
  @Path("{username}")
  @Consumes(MediaType.APPLICATION_JSON)
  @jakarta.ws.rs.Produces(MediaType.APPLICATION_JSON)
  public Response verifyPassword(
      @PathParam("username") String username,
      @HeaderParam("Authorization") String authorization,
      Map<String, String> body) {

    RealmModel realm = session.getContext().getRealm();
    if (!authorize(realm, authorization)) return Response.status(401).build();
    WorkOSHttpClient w = client(realm);
    if (w == null) return Response.status(503).build();

    String clientId = realm.getAttribute(AttributeKeys.REALM_SLOW_CLIENT_ID);
    String clientSecret = realm.getAttribute(AttributeKeys.REALM_SLOW_CLIENT_SECRET);
    if (clientId == null || clientSecret == null) return Response.status(503).build();

    String password = body == null ? null : body.get("password");
    if (password == null) return Response.status(400).build();

    boolean ok;
    try {
      ok = w.authenticatePassword(username, password, clientId, clientSecret);
    } catch (Exception e) {
      log.debugf("password verify error for %s: %s", username, e.toString());
      ok = false;
    }
    return ok
        ? Response.ok("{\"ok\":true}", MediaType.APPLICATION_JSON).build()
        : Response.status(401).build();
  }

  private static ArrayNode arr(String v) {
    ArrayNode a = JsonUtil.mapper().createArrayNode();
    a.add(v);
    return a;
  }

  private boolean authorize(RealmModel realm, String header) {
    if (header == null || !header.startsWith("Bearer ")) return false;
    String supplied = header.substring("Bearer ".length()).trim();
    String expected = realm.getAttribute(AttributeKeys.REALM_SLOW_TOKEN);
    return expected != null
        && java.security.MessageDigest.isEqual(
            supplied.getBytes(java.nio.charset.StandardCharsets.US_ASCII),
            expected.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
  }

  private WorkOSHttpClient client(RealmModel realm) {
    String apiKey = realm.getAttribute("workos.migration.api_key");
    String apiBase =
        Optional.ofNullable(realm.getAttribute("workos.migration.api_base_url"))
            .orElse("https://api.workos.com");
    if (apiKey == null) return null;
    return new WorkOSHttpClient(apiBase, apiKey);
  }
}
