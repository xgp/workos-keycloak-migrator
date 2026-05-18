package io.phasetwo.wkm.it;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.keycloak.representations.idm.ClientRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.RoleRepresentation;
import org.keycloak.representations.idm.UserRepresentation;

/**
 * Realm bootstrap mirroring {@code scripts/bootstrap-realm.sh}. Creates a target realm with the
 * settings the migrator + extensions expect (sslRequired=NONE, unmanagedAttributePolicy=ENABLED)
 * plus a service-account client with realm-admin.
 */
public final class KeycloakStack {

    private static final ObjectMapper JSON = new ObjectMapper();

    private KeycloakStack() {}

    public record Stack(
            String baseUrl,
            String realm,
            String migratorClientId,
            String migratorClientSecret) {}

    public static Stack bootstrap(String baseUrl, String realmName) {
        Keycloak admin = KeycloakBuilder.builder()
                .serverUrl(baseUrl)
                .realm("master")
                .clientId("admin-cli")
                .username("admin")
                .password("admin")
                .grantType(OAuth2Constants.PASSWORD)
                .build();
        try {
            disableMasterSsl(admin);
            createOrUpdateRealm(admin, realmName);
            enableUnmanagedAttributePolicy(baseUrl, admin.tokenManager().getAccessTokenString(), realmName);
            String secret = createMigratorClient(admin, realmName);
            return new Stack(baseUrl, realmName, "migrator-cli", secret);
        } finally {
            admin.close();
        }
    }

    private static void disableMasterSsl(Keycloak admin) {
        RealmRepresentation master = admin.realm("master").toRepresentation();
        master.setSslRequired("NONE");
        admin.realm("master").update(master);
    }

    private static void createOrUpdateRealm(Keycloak admin, String realmName) {
        boolean exists = admin.realms().findAll().stream().anyMatch(r -> realmName.equals(r.getRealm()));
        if (!exists) {
            RealmRepresentation r = new RealmRepresentation();
            r.setRealm(realmName);
            r.setEnabled(true);
            r.setSslRequired("NONE");
            r.setLoginWithEmailAllowed(true);
            r.setRegistrationEmailAsUsername(true);
            r.setDuplicateEmailsAllowed(false);
            admin.realms().create(r);
        } else {
            RealmRepresentation existing = admin.realm(realmName).toRepresentation();
            existing.setSslRequired("NONE");
            admin.realm(realmName).update(existing);
        }
    }

    /**
     * The /users/profile endpoint isn't exposed by the admin client at this version, so we use
     * a plain JAX-RS call with the admin's current token.
     */
    private static void enableUnmanagedAttributePolicy(String baseUrl, String token, String realm) {
        String url = baseUrl + "/admin/realms/" + realm + "/users/profile";
        Client client = ClientBuilder.newClient();
        try {
            String body = client.target(url)
                    .request(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + token)
                    .get(String.class);
            Map<String, Object> root = JSON.readValue(body, new TypeReference<>() {});
            root.put("unmanagedAttributePolicy", "ENABLED");
            String updated = JSON.writeValueAsString(root);
            try (Response resp = client.target(url)
                    .request(MediaType.APPLICATION_JSON)
                    .header("Authorization", "Bearer " + token)
                    .put(Entity.json(updated))) {
                if (resp.getStatus() >= 300) {
                    throw new IllegalStateException("PUT users/profile failed: " + resp.getStatus());
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        } finally {
            client.close();
        }
    }

    private static String createMigratorClient(Keycloak admin, String realm) {
        String clientId = "migrator-cli";
        boolean exists = !admin.realm(realm).clients().findByClientId(clientId).isEmpty();
        if (!exists) {
            ClientRepresentation c = new ClientRepresentation();
            c.setClientId(clientId);
            c.setEnabled(true);
            c.setPublicClient(false);
            c.setServiceAccountsEnabled(true);
            c.setStandardFlowEnabled(false);
            c.setDirectAccessGrantsEnabled(false);
            c.setProtocol("openid-connect");
            try (Response r = admin.realm(realm).clients().create(c)) {
                if (r.getStatus() != 201) {
                    throw new IllegalStateException("client create returned " + r.getStatus());
                }
            }
        }
        String clientUuid = admin.realm(realm).clients().findByClientId(clientId).get(0).getId();
        String secret = admin.realm(realm).clients().get(clientUuid).getSecret().getValue();
        UserRepresentation sa = admin.realm(realm).clients().get(clientUuid).getServiceAccountUser();
        String realmMgmtClientUuid = admin.realm(realm).clients().findByClientId("realm-management").get(0).getId();
        RoleRepresentation realmAdmin = admin.realm(realm).clients().get(realmMgmtClientUuid).roles().get("realm-admin").toRepresentation();
        admin.realm(realm).users().get(sa.getId())
                .roles().clientLevel(realmMgmtClientUuid).add(List.of(realmAdmin));
        return secret;
    }
}
