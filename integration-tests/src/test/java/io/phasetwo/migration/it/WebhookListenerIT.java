package io.phasetwo.migration.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.phasetwo.migration.common.webhook.WebhookVerifier;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Drives the workos-webhook resource: signs payloads with a known secret, verifies the resource
 * accepts good signatures, rejects bad ones, and applies in-process mutations (user.deleted).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WebhookListenerIT {

    private static final String REALM = "wkm-webhook-it";
    private static final String SECRET = "whsec_it_secret";
    private static final String PUBLIC_ID = UUID.randomUUID().toString();

    @Container
    static final PhaseTwoKeycloakContainer kc = new PhaseTwoKeycloakContainer()
            .withProvidersDir(Paths.get(System.getProperty("wkm.providers.dir", "target/providers")));

    private KeycloakStack.Stack stack;
    private Keycloak realmAdmin;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @BeforeAll
    void setUp() {
        kc.disableMasterSsl();
        stack = KeycloakStack.bootstrap(kc.baseUrl(), REALM);
        realmAdmin = KeycloakBuilder.builder()
                .serverUrl(stack.baseUrl())
                .realm(stack.realm())
                .clientId(stack.migratorClientId())
                .clientSecret(stack.migratorClientSecret())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build();
        seedWebhookAttributes();
        seedExistingUser();
    }

    @AfterAll
    void tearDown() {
        if (realmAdmin != null) realmAdmin.close();
    }

    @Test
    @Order(1)
    void validSignature_returns_200() throws Exception {
        String body = "{\"id\":\"evt_1\",\"event\":\"user.updated\",\"data\":{\"id\":\"user_TEST_alice\",\"email\":\"alice@acme.example.com\",\"first_name\":\"Alice\",\"last_name\":\"Updated\"},\"created_at\":\"2026-01-10T12:00:00Z\"}";
        HttpResponse<String> resp = post(body, sign(body));
        assertThat(resp.statusCode()).isEqualTo(200);
        // user.updated applies in-process — last name should be "Updated"
        UserRepresentation u = findExistingUser();
        assertThat(u.getLastName()).isEqualTo("Updated");
    }

    @Test
    @Order(2)
    void badSignature_returns_401() throws Exception {
        String body = "{\"id\":\"evt_2\",\"event\":\"user.updated\",\"data\":{}}";
        HttpResponse<String> resp = post(body, "t=" + Instant.now().toEpochMilli() + ",v1=deadbeef");
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    @Order(3)
    void missingSignature_returns_401() throws Exception {
        String body = "{\"id\":\"evt_3\",\"event\":\"user.updated\",\"data\":{}}";
        HttpResponse<String> resp = post(body, null);
        assertThat(resp.statusCode()).isEqualTo(401);
    }

    @Test
    @Order(4)
    void unknownPublicId_returns_404() throws Exception {
        String body = "{\"id\":\"evt_4\",\"event\":\"user.updated\",\"data\":{}}";
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(stack.baseUrl() + "/realms/" + REALM + "/workos-webhook/not-the-right-id"))
                .header("Content-Type", "application/json")
                .header("WorkOS-Signature", sign(body))
                .POST(BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> resp = http.send(req, BodyHandlers.ofString());
        assertThat(resp.statusCode()).isEqualTo(404);
    }

    @Test
    @Order(5)
    void userDeleted_removes_user() throws Exception {
        String body = "{\"id\":\"evt_5\",\"event\":\"user.deleted\",\"data\":{\"id\":\"user_TEST_alice\"},\"created_at\":\"2026-01-10T12:00:00Z\"}";
        HttpResponse<String> resp = post(body, sign(body));
        assertThat(resp.statusCode()).isEqualTo(200);
        var hits = realmAdmin.realm(REALM).users().searchByEmail("alice@acme.example.com", true);
        assertThat(hits).isEmpty();
    }

    private HttpResponse<String> post(String body, String signatureHeader) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(stack.baseUrl() + "/realms/" + REALM + "/workos-webhook/" + PUBLIC_ID))
                .header("Content-Type", "application/json")
                .POST(BodyPublishers.ofString(body));
        if (signatureHeader != null) b.header("WorkOS-Signature", signatureHeader);
        return http.send(b.build(), BodyHandlers.ofString());
    }

    private String sign(String body) {
        long t = Instant.now().toEpochMilli();
        String v1 = WebhookVerifier.hmacSha256Hex(SECRET, t + "." + body);
        return "t=" + t + ",v1=" + v1;
    }

    private void seedWebhookAttributes() {
        RealmResource realm = realmAdmin.realm(REALM);
        RealmRepresentation rep = realm.toRepresentation();
        Map<String, String> attrs = rep.getAttributes() == null ? new HashMap<>() : new HashMap<>(rep.getAttributes());
        attrs.put("workos.migration.webhook.public_id", PUBLIC_ID);
        attrs.put("workos.migration.webhook.secret", SECRET);
        rep.setAttributes(attrs);
        realm.update(rep);
    }

    private void seedExistingUser() {
        UserRepresentation u = new UserRepresentation();
        u.setUsername("alice@acme.example.com");
        u.setEmail("alice@acme.example.com");
        u.setEnabled(true);
        Map<String, java.util.List<String>> attrs = new HashMap<>();
        attrs.put("workos.id", java.util.List.of("user_TEST_alice"));
        u.setAttributes(attrs);
        try (var r = realmAdmin.realm(REALM).users().create(u)) {
            if (r.getStatus() != 201) throw new IllegalStateException("seed user failed: " + r.getStatus());
        }
    }

    private UserRepresentation findExistingUser() {
        var hits = realmAdmin.realm(REALM).users().searchByEmail("alice@acme.example.com", true);
        assertThat(hits).hasSize(1);
        return realmAdmin.realm(REALM).users().get(hits.get(0).getId()).toRepresentation();
    }
}
