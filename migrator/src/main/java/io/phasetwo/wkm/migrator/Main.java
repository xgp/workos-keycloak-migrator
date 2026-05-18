package io.phasetwo.wkm.migrator;

import io.phasetwo.client.PhaseTwo;
import io.phasetwo.wkm.common.AttributeKeys;
import io.phasetwo.wkm.common.state.Counters;
import io.phasetwo.wkm.common.state.MigrationState;
import io.phasetwo.wkm.common.sync.SyncAction;
import io.phasetwo.wkm.common.sync.SyncContext;
import io.phasetwo.wkm.common.util.Hashes;
import io.phasetwo.wkm.common.workos.WorkOSHttpClient;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import org.keycloak.OAuth2Constants;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.KeycloakBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import picocli.CommandLine;

@CommandLine.Command(
        name = "workos-keycloak-migrator",
        mixinStandardHelpOptions = true,
        description = "Migrate WorkOS entities into Keycloak with Phase Two extensions.",
        versionProvider = Main.VersionProvider.class)
public class Main implements Callable<Integer> {

    private static final Logger log = LoggerFactory.getLogger(Main.class);

    @CommandLine.Option(names = "--workos-api-key", required = true,
            description = "WorkOS API key (sk_...). May also be set via WORKOS_API_KEY env var.",
            defaultValue = "${WORKOS_API_KEY}")
    String workosApiKey;

    @CommandLine.Option(names = "--workos-base-url", defaultValue = "https://api.workos.com")
    String workosBaseUrl;

    @CommandLine.Option(names = "--keycloak-url", required = true)
    String keycloakUrl;

    @CommandLine.Option(names = "--keycloak-realm", required = true)
    String keycloakRealm;

    @CommandLine.Option(names = "--keycloak-client-id", required = true)
    String keycloakClientId;

    @CommandLine.Option(names = "--keycloak-client-secret", required = true)
    String keycloakClientSecret;

    @CommandLine.Option(names = "--entities", defaultValue = "all",
            description = "Comma-separated: all,roles,organizations,organization_roles,idps,directories,users,memberships")
    String entitiesArg;

    @CommandLine.Option(names = "--source-label",
            description = "Override the workos.source attribute. Defaults to a fingerprint of the API key.")
    String sourceLabel;

    @CommandLine.Option(names = "--dry-run", defaultValue = "false")
    boolean dryRun;

    @CommandLine.Option(names = "--restart", defaultValue = "false",
            description = "Clear all workos.migration.cursor.* before running.")
    boolean restart;

    @CommandLine.Option(names = "--limit", defaultValue = "0",
            description = "Cap entities per type for testing. 0 = unlimited.")
    int limit;

    @CommandLine.Option(names = "--page-size", defaultValue = "100")
    int pageSize;

    @CommandLine.Option(names = "--force-rebind", defaultValue = "false",
            description = "Override the api-key fingerprint check.")
    boolean forceRebind;

    @CommandLine.Option(names = "--slow-migration-active", defaultValue = "false",
            description = "Tell user sync that the slow-migration extension is live; skips UPDATE_PASSWORD.")
    boolean slowMigrationActive;

    @CommandLine.Option(names = "--continue-on-error", defaultValue = "true")
    boolean continueOnError;

    public static void main(String[] args) {
        int code = new CommandLine(new Main()).execute(args);
        System.exit(code);
    }

    @Override
    public Integer call() {
        log.info("Starting WorkOS → Keycloak migration into realm {} at {}", keycloakRealm, keycloakUrl);

        Set<String> entities = parseEntities(entitiesArg);
        String fingerprint = Hashes.fingerprint(workosApiKey);
        String source = sourceLabel != null && !sourceLabel.isBlank() ? sourceLabel : fingerprint;

        Keycloak keycloak = KeycloakBuilder.builder()
                .serverUrl(keycloakUrl)
                .realm(keycloakRealm)
                .clientId(keycloakClientId)
                .clientSecret(keycloakClientSecret)
                .grantType(OAuth2Constants.CLIENT_CREDENTIALS)
                .build();

        PhaseTwo phaseTwo = new PhaseTwo(keycloak, keycloakUrl.replaceAll("/+$", ""));

        WorkOSHttpClient workos = new WorkOSHttpClient(workosBaseUrl, workosApiKey);

        MigrationState state = new MigrationState(keycloak.realm(keycloakRealm));
        String stored = state.get(AttributeKeys.REALM_CLIENT_FINGERPRINT);
        if (stored != null && !stored.equals(fingerprint) && !forceRebind) {
            log.error("Realm {} has client_fingerprint={} but supplied API key fingerprints to {}. Use --force-rebind to override.",
                    keycloakRealm, stored, fingerprint);
            return 2;
        }
        state.set(AttributeKeys.REALM_CLIENT_FINGERPRINT, fingerprint);

        if (restart) {
            log.warn("--restart: clearing all workos.migration.cursor.* attributes");
            state.clearCursors();
        }

        SyncContext ctx = new SyncContext(workos, keycloak, phaseTwo, keycloakRealm, source, dryRun, slowMigrationActive);
        Counters counters = new Counters();
        Steps steps = new Steps(ctx, state, counters, pageSize, limit);

        boolean ok = true;
        try {
            if (entities.contains("roles")) safe(steps::runRealmRoles);
            if (entities.contains("organizations")) safe(steps::runOrganizations);
            if (entities.contains("organization_roles")) safe(steps::runOrganizationRoles);
            if (entities.contains("idps")) safe(steps::runIdps);
            if (entities.contains("directories")) safe(() -> steps.runDirectories(keycloakUrl));
            if (entities.contains("users")) safe(steps::runUsers);
            if (entities.contains("memberships")) safe(steps::runMemberships);
        } catch (Exception e) {
            ok = false;
            log.error("aborted: {}", e.toString(), e);
        }

        // Persist run state
        steps.persistCounters();
        state.set(AttributeKeys.REALM_LAST_RUN_AT, Instant.now().toString());
        state.set(AttributeKeys.REALM_LAST_RUN_STATUS, ok ? "OK" : "FAILED");

        // Final report
        log.info("================ Migration summary ================");
        counters.snapshot().forEach((entity, byAction) ->
                log.info("{}: {}", entity, byAction));
        log.info("Total failed: {}", counters.total(SyncAction.FAILED));

        return (ok && (continueOnError || counters.total(SyncAction.FAILED) == 0)) ? 0 : 1;
    }

    private void safe(Runnable r) {
        try {
            r.run();
        } catch (Exception e) {
            log.error("step failed: {}", e.toString(), e);
            if (!continueOnError) throw e;
        }
    }

    private static Set<String> parseEntities(String arg) {
        Set<String> all = new HashSet<>(Arrays.asList(
                "roles", "organizations", "organization_roles", "idps", "directories", "users", "memberships"));
        if (arg == null || arg.isBlank() || arg.equalsIgnoreCase("all")) return all;
        Set<String> out = new HashSet<>();
        for (String e : arg.split(",")) {
            String t = e.trim().toLowerCase();
            if (!all.contains(t)) throw new IllegalArgumentException("unknown entity: " + t);
            out.add(t);
        }
        return out;
    }

    public static class VersionProvider implements CommandLine.IVersionProvider {
        @Override
        public String[] getVersion() {
            return new String[]{"workos-keycloak-migrator 0.1.0"};
        }
    }
}
