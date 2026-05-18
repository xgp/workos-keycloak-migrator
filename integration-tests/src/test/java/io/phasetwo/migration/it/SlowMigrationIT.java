package io.phasetwo.migration.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;

/**
 * Drives the workos-legacy resource end-to-end against a live Keycloak with the slow-migration
 * extension installed and a WireMock-backed WorkOS userinfo / authenticate API.
 *
 * <p>This IT does <em>not</em> use {@code @Container}-managed lifecycle because the WireMock
 * server's host port must be exposed to Testcontainers <em>before</em> the Keycloak container
 * starts (otherwise {@code host.testcontainers.internal} won't route from inside the container).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SlowMigrationIT {

  private static final String REALM = "wkm-slow-it";
  private static final String TOKEN = "test-bearer-token";
  private static final String API_KEY = "test-workos-key";
  private static final String CLIENT_ID = "test-workos-client";
  private static final String CLIENT_SECRET = "test-workos-secret";

  private PhaseTwoKeycloakContainer kc;
  private WorkOSStub workos;
  private KeycloakStack.Stack stack;
  private Keycloak realmAdmin;
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
  private final ObjectMapper mapper = new ObjectMapper();

  @BeforeAll
  void setUp() {
    // Start WireMock first so its port is exposed to Testcontainers before the container boots.
    workos = new WorkOSStub();
    stubWorkos();

    kc =
        new PhaseTwoKeycloakContainer()
            .withProvidersDir(
                Paths.get(System.getProperty("wkm.providers.dir", "target/providers")));
    kc.start();
    kc.disableMasterSsl();
    stack = KeycloakStack.bootstrap(kc.baseUrl(), REALM);
    realmAdmin =
        KeycloakBuilder.builder()
            .serverUrl(stack.baseUrl())
            .realm(stack.realm())
            .clientId(stack.migratorClientId())
            .clientSecret(stack.migratorClientSecret())
            .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
            .build();
    seedSlowMigrationAttributes();
  }

  @AfterAll
  void tearDown() {
    if (realmAdmin != null) realmAdmin.close();
    if (workos != null) workos.close();
    if (kc != null) kc.stop();
  }

  @Test
  void federationComponent_can_be_installed_with_upstream_provider_id() {
    // Verify the upstream provider id is available — the extension's postInit auto-install
    // runs once at boot, before our realm exists in IT scope, so we install the component
    // manually here to assert the integration with keycloak-rest-provider works.
    ComponentRepresentation cm = new ComponentRepresentation();
    cm.setName("workos-legacy-migration-it");
    cm.setProviderType("org.keycloak.storage.UserStorageProvider");
    cm.setProviderId("User migration using a REST client");
    cm.setParentId(realmAdmin.realm(REALM).toRepresentation().getId());
    cm.setConfig(new org.keycloak.common.util.MultivaluedHashMap<>());
    cm.getConfig().putSingle("URI", stack.baseUrl() + "/realms/" + REALM + "/workos-legacy");
    cm.getConfig().putSingle("API_TOKEN_ENABLED", "true");
    cm.getConfig().putSingle("API_TOKEN", TOKEN);
    try (var r = realmAdmin.realm(REALM).components().add(cm)) {
      assertThat(r.getStatus()).isEqualTo(201);
    }
    var components =
        realmAdmin
            .realm(REALM)
            .components()
            .query(
                realmAdmin.realm(REALM).toRepresentation().getId(),
                "org.keycloak.storage.UserStorageProvider");
    assertThat(components)
        .anyMatch(
            c ->
                "workos-legacy-migration-it".equals(c.getName())
                    && "User migration using a REST client".equals(c.getProviderId()));
  }

  @Test
  void getKnownUser_returns_200_with_workos_shape() throws Exception {
    HttpResponse<String> resp = get("alice@acme.example.com", TOKEN);
    assertThat(resp.statusCode()).isEqualTo(200);
    JsonNode body = mapper.readTree(resp.body());
    assertThat(body.path("username").asText()).isEqualTo("alice@acme.example.com");
    assertThat(body.path("email").asText()).isEqualTo("alice@acme.example.com");
    assertThat(body.path("emailVerified").asBoolean()).isTrue();
    assertThat(body.path("attributes").path("workos.id").get(0).asText())
        .isEqualTo("user_TEST_alice");
    assertThat(body.has("organizations")).as("organizations[] must be omitted per spec").isFalse();
  }

  @Test
  void getUnknownUser_returns_404() throws Exception {
    HttpResponse<String> resp = get("nobody@nowhere.example.com", TOKEN);
    assertThat(resp.statusCode()).isEqualTo(404);
  }

  @Test
  void missingBearer_returns_401() throws Exception {
    HttpResponse<String> resp = get("alice@acme.example.com", null);
    assertThat(resp.statusCode()).isEqualTo(401);
  }

  @Test
  void wrongBearer_returns_401() throws Exception {
    HttpResponse<String> resp = get("alice@acme.example.com", "wrong-token");
    assertThat(resp.statusCode()).isEqualTo(401);
  }

  @Test
  void postCorrectPassword_returns_200() throws Exception {
    workos
        .server()
        .stubFor(
            WireMock.post(WireMock.urlPathEqualTo("/user_management/authenticate"))
                .willReturn(WireMock.aResponse().withStatus(200).withBody("{\"user\":{}}")));
    HttpResponse<String> resp = postPassword("alice@acme.example.com", "correct-password", TOKEN);
    assertThat(resp.statusCode()).isEqualTo(200);
  }

  @Test
  void postWrongPassword_returns_401() throws Exception {
    workos
        .server()
        .stubFor(
            WireMock.post(WireMock.urlPathEqualTo("/user_management/authenticate"))
                .willReturn(WireMock.aResponse().withStatus(401)));
    HttpResponse<String> resp = postPassword("alice@acme.example.com", "wrong-password", TOKEN);
    assertThat(resp.statusCode()).isEqualTo(401);
  }

  private HttpResponse<String> get(String username, String bearer) throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder()
            .uri(URI.create(stack.baseUrl() + "/realms/" + REALM + "/workos-legacy/" + username))
            .GET();
    if (bearer != null) b.header("Authorization", "Bearer " + bearer);
    return http.send(b.build(), BodyHandlers.ofString());
  }

  private HttpResponse<String> postPassword(String username, String password, String bearer)
      throws Exception {
    HttpRequest.Builder b =
        HttpRequest.newBuilder()
            .uri(URI.create(stack.baseUrl() + "/realms/" + REALM + "/workos-legacy/" + username))
            .header("Content-Type", "application/json")
            .POST(BodyPublishers.ofString("{\"password\":\"" + password + "\"}"));
    if (bearer != null) b.header("Authorization", "Bearer " + bearer);
    return http.send(b.build(), BodyHandlers.ofString());
  }

  private void stubWorkos() {
    // GET /user_management/users?email=alice → 1 user, else empty
    workos
        .server()
        .stubFor(
            WireMock.get(WireMock.urlPathEqualTo("/user_management/users"))
                .withQueryParam("email", WireMock.equalTo("alice@acme.example.com"))
                .willReturn(WireMock.okJson(WorkOSStub.loadFixture("user-alice-by-email.json"))));
    workos
        .server()
        .stubFor(
            WireMock.get(WireMock.urlPathEqualTo("/user_management/users"))
                .withQueryParam("email", WireMock.equalTo("nobody@nowhere.example.com"))
                .willReturn(WireMock.okJson(WorkOSStub.loadFixture("user-empty.json"))));
    workos
        .server()
        .stubFor(
            WireMock.get(
                    WireMock.urlPathEqualTo("/user_management/users/user_TEST_alice/identities"))
                .willReturn(WireMock.okJson("[]")));
  }

  private void seedSlowMigrationAttributes() {
    var realm = realmAdmin.realm(REALM);
    RealmRepresentation rep = realm.toRepresentation();
    Map<String, String> attrs =
        rep.getAttributes() == null ? new HashMap<>() : new HashMap<>(rep.getAttributes());
    attrs.put("workos.migration.slow.token", TOKEN);
    attrs.put("workos.migration.api_key", API_KEY);
    attrs.put("workos.migration.api_base_url", workos.containerUrl());
    attrs.put("workos.migration.slow.client_id", CLIENT_ID);
    attrs.put("workos.migration.slow.client_secret", CLIENT_SECRET);
    rep.setAttributes(attrs);
    realm.update(rep);
  }
}
