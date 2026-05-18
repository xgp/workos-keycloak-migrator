package io.phasetwo.wkm.it;

import static org.assertj.core.api.Assertions.assertThat;

import io.phasetwo.wkm.migrator.Main;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
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
import picocli.CommandLine;

/**
 * Drives the bulk migrator against a WireMock-backed WorkOS and a live phasetwo-keycloak container.
 * Asserts the happy path (CREATED counts) and then re-runs to assert idempotency
 * (SKIPPED reason=unchanged).
 */
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BulkMigratorIT {

    private static final String REALM = "wkm-it";

    @Container
    static final PhaseTwoKeycloakContainer kc = new PhaseTwoKeycloakContainer();

    private WorkOSStub workos;
    private KeycloakStack.Stack stack;
    private Keycloak realmAdmin;

    @BeforeAll
    void setUp() {
        kc.disableMasterSsl();
        workos = new WorkOSStub();
        stubWorkos();
        stack = KeycloakStack.bootstrap(kc.baseUrl(), REALM);
        realmAdmin = KeycloakBuilder.builder()
                .serverUrl(stack.baseUrl())
                .realm(stack.realm())
                .clientId(stack.migratorClientId())
                .clientSecret(stack.migratorClientSecret())
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build();
    }

    @AfterAll
    void tearDown() {
        if (realmAdmin != null) realmAdmin.close();
        if (workos != null) workos.close();
    }

    @Test
    @Order(1)
    void happyPath_imports_workos_state() {
        try (LogCapture log = new LogCapture("io.phasetwo.wkm.migrator.Steps")) {
            int code = runMigrator();
            assertThat(code).isEqualTo(0);

            List<String> messages = log.messages().collect(Collectors.toList());
            assertThat(messages).anyMatch(m -> m.contains("== Step: environment roles =="));
            assertThat(messages).anyMatch(m -> m.contains("== Step: organizations =="));
            assertThat(messages).anyMatch(m -> m.contains("== Step: users =="));

            assertCreated(messages, "role", 2);
            assertCreated(messages, "organization", 3);
            assertCreated(messages, "user", 3);
            assertPartial(messages, "identity_provider", 2);
        }

        // Verify Keycloak state via the admin client
        RealmResource realm = realmAdmin.realm(stack.realm());

        // Environment roles
        var roles = realm.roles().list();
        assertThat(roles).extracting("name").contains("wkm-admin", "wkm-member");

        // Users
        var users = realm.users().searchByEmail("alice@acme.example.com", true);
        assertThat(users).hasSize(1);
        UserRepresentation alice = realm.users().get(users.get(0).getId()).toRepresentation();
        Map<String, List<String>> attrs = alice.getAttributes();
        assertThat(attrs).isNotNull();
        assertThat(attrs.get("workos.id")).containsExactly("user_TEST_alice");
        assertThat(attrs.get("workos.external_id")).containsExactly("ext_alice");
        assertThat(attrs.get("workos.locale")).containsExactly("en-US");
        assertThat(attrs.get("workos.metadata.timezone")).containsExactly("America/New_York");
        assertThat(attrs).containsKey("workos.sync_hash");

        // Migration state on the realm
        RealmRepresentation r = realm.toRepresentation();
        Map<String, String> rattrs = r.getAttributes();
        assertThat(rattrs).containsKey("workos.migration.last_run_at");
        assertThat(rattrs.get("workos.migration.last_run_status")).isEqualTo("OK");
        assertThat(rattrs.get("workos.migration.client_fingerprint")).isNotBlank();
    }

    @Test
    @Order(2)
    void idempotency_rerun_is_noop_for_users() {
        try (LogCapture log = new LogCapture("io.phasetwo.wkm.migrator.Steps")) {
            int code = runMigrator();
            assertThat(code).isEqualTo(0);

            List<String> messages = log.messages().collect(Collectors.toList());
            // Every user line should now be SKIPPED reason=unchanged
            List<String> userLines = messages.stream()
                    .filter(m -> m.startsWith("user user_TEST_"))
                    .collect(Collectors.toList());
            assertThat(userLines).hasSize(3);
            assertThat(userLines).allMatch(m -> m.contains("SKIPPED") && m.contains("reason=unchanged"));

            // No CREATED user line in this run
            assertThat(messages).noneMatch(m -> m.startsWith("user user_TEST_") && m.contains("CREATED"));
        }
    }

    private int runMigrator() {
        String fingerprint = io.phasetwo.wkm.common.util.Hashes.fingerprint("test-key");
        // Reset prior run state's fingerprint to allow the next run to bind freely.
        return new CommandLine(new Main()).execute(
                "--workos-api-key=test-key",
                "--workos-base-url=" + workos.hostUrl(),
                "--keycloak-url=" + stack.baseUrl(),
                "--keycloak-realm=" + stack.realm(),
                "--keycloak-client-id=" + stack.migratorClientId(),
                "--keycloak-client-secret=" + stack.migratorClientSecret(),
                "--source-label=integration-test",
                "--page-size=10");
    }

    private void stubWorkos() {
        workos.stubJson("/organizations", "organizations.json");
        workos.stubJson("/user_management/users", "users.json");
        workos.stubJson("/authorization/roles", "env-roles.json");
        workos.stubJson("/connections", "connections.json");
        workos.stubJson("/directories", "directories-empty.json");
        workos.stubJson("/user_management/organization_memberships", "memberships-empty.json");

        // Per-org roles (empty)
        workos.stubJson("/authorization/organizations/org_TEST_acme/roles", "org-roles-empty.json");
        workos.stubJson("/authorization/organizations/org_TEST_globex/roles", "org-roles-empty.json");
        workos.stubJson("/authorization/organizations/org_TEST_initech/roles", "org-roles-empty.json");

        // Per-user identities (empty)
        workos.stubJson("/user_management/users/user_TEST_alice/identities", "users-identities-empty.json");
        workos.stubJson("/user_management/users/user_TEST_bob/identities", "users-identities-empty.json");
        workos.stubJson("/user_management/users/user_TEST_carol/identities", "users-identities-empty.json");
    }

    private void assertCreated(List<String> messages, String entityType, int n) {
        long created = messages.stream()
                .filter(m -> m.startsWith(entityType + " "))
                .filter(m -> m.contains("CREATED"))
                .count();
        assertThat(created).as("CREATED %s lines", entityType).isEqualTo(n);
    }

    private void assertPartial(List<String> messages, String entityType, int n) {
        long partial = messages.stream()
                .filter(m -> m.startsWith(entityType + " "))
                .filter(m -> m.contains("PARTIAL"))
                .count();
        assertThat(partial).as("PARTIAL %s lines", entityType).isEqualTo(n);
    }
}
