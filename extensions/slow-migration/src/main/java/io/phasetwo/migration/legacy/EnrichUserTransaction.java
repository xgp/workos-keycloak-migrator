package io.phasetwo.migration.legacy;

import io.phasetwo.migration.common.workos.WorkOSHttpClient;
import lombok.extern.jbosslog.JBossLog;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakTransaction;
import org.keycloak.models.RealmModel;
import org.keycloak.models.utils.KeycloakModelUtils;

/**
 * A no-op-during-the-main-txn {@link KeycloakTransaction} that, on {@code commit()}, opens a
 * fresh Keycloak session and runs {@link SessionUserSync#enrich} for the supplied email. Enlisted
 * by {@link WorkOSLegacyResource#getUser} via {@code session.getTransactionManager().enlistAfterCompletion(...)}.
 *
 * <p>This runs <em>after</em> Keycloak's own legacy-import transaction has committed, so by the
 * time we open our follow-up session the imported {@link org.keycloak.models.UserModel} exists in
 * the database and we can grant roles, federated identities, and Phase Two organisation
 * memberships safely.
 */
@JBossLog
final class EnrichUserTransaction implements KeycloakTransaction {

    private final KeycloakSession session;
    private final String realmName;
    private final String email;
    private final String apiBaseUrl;
    private final String apiKey;
    private boolean active = true;
    private boolean rollbackOnly;

    EnrichUserTransaction(KeycloakSession session, String realmName, String email,
                          String apiBaseUrl, String apiKey) {
        this.session = session;
        this.realmName = realmName;
        this.email = email;
        this.apiBaseUrl = apiBaseUrl;
        this.apiKey = apiKey;
    }

    @Override public void begin() {}

    @Override
    public void commit() {
        active = false;
        try {
            KeycloakModelUtils.runJobInTransaction(session.getKeycloakSessionFactory(), s -> {
                RealmModel realm = s.realms().getRealmByName(realmName);
                if (realm == null) {
                    log.debugf("realm %s missing during after-commit enrich; skipping", realmName);
                    return;
                }
                s.getContext().setRealm(realm);
                WorkOSHttpClient w = new WorkOSHttpClient(apiBaseUrl, apiKey);
                new SessionUserSync(s, realm, w).enrich(email);
            });
        } catch (Throwable t) {
            // Never throw from the after-completion path: it would surface as a 500 on the
            // already-committed legacy GET response, confusing the keycloak-user-migration caller.
            log.warnf("after-commit enrich for %s failed: %s", email, t.toString());
        }
    }

    @Override public void rollback() { active = false; }
    @Override public void setRollbackOnly() { rollbackOnly = true; }
    @Override public boolean getRollbackOnly() { return rollbackOnly; }
    @Override public boolean isActive() { return active; }
}
