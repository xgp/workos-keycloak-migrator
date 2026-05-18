package io.phasetwo.migration.common.sync;

import io.phasetwo.client.PhaseTwo;
import io.phasetwo.migration.common.workos.WorkOSClient;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;

/**
 * Shared context passed to every synchroniser — single source of clients/realm/source label so we
 * don't end up with a long constructor parameter list.
 */
public final class SyncContext {

    private final WorkOSClient workos;
    private final Keycloak keycloak;
    private final PhaseTwo phaseTwo;
    private final String realmName;
    private final RealmResource realm;
    private final String source;
    private final boolean dryRun;
    /** True when the slow-migration extension is paired with this run — affects requiredActions. */
    private final boolean slowMigrationActive;

    public SyncContext(
            WorkOSClient workos,
            Keycloak keycloak,
            PhaseTwo phaseTwo,
            String realmName,
            String source,
            boolean dryRun,
            boolean slowMigrationActive) {
        this.workos = workos;
        this.keycloak = keycloak;
        this.phaseTwo = phaseTwo;
        this.realmName = realmName;
        this.realm = keycloak.realm(realmName);
        this.source = source;
        this.dryRun = dryRun;
        this.slowMigrationActive = slowMigrationActive;
    }

    public WorkOSClient workos() { return workos; }
    public Keycloak keycloak() { return keycloak; }
    public PhaseTwo phaseTwo() { return phaseTwo; }
    public String realmName() { return realmName; }
    public RealmResource realm() { return realm; }
    public String source() { return source; }
    public boolean dryRun() { return dryRun; }
    public boolean slowMigrationActive() { return slowMigrationActive; }
}
