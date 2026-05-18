package io.phasetwo.wkm.it;

import java.nio.file.Path;
import java.time.Duration;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Phase Two Keycloak container with file-based dev storage. Use {@link #withProvidersDir(Path)} to
 * mount our extension jars + the keycloak-rest-provider jar.
 */
public class PhaseTwoKeycloakContainer extends GenericContainer<PhaseTwoKeycloakContainer> {

    public PhaseTwoKeycloakContainer() {
        this(
                System.getProperty("wkm.phasetwo.image", "quay.io/phasetwo/phasetwo-keycloak"),
                System.getProperty("wkm.phasetwo.tag", "26.5.7"));
    }

    public PhaseTwoKeycloakContainer(String image, String tag) {
        super(DockerImageName.parse(image + ":" + tag));
        withExposedPorts(8080, 9000);
        withEnv("KC_DB", "dev-file");
        withEnv("KEYCLOAK_ADMIN", "admin");
        withEnv("KEYCLOAK_ADMIN_PASSWORD", "admin");
        withEnv("KC_HOSTNAME_STRICT", "false");
        withEnv("KC_HOSTNAME_STRICT_HTTPS", "false");
        withEnv("KC_HTTP_ENABLED", "true");
        withEnv("KC_HEALTH_ENABLED", "true");
        // start-dev keeps boot quick and disables SSL on most paths; we still need
        // sslRequired=NONE on individual realms which the bootstrap step handles.
        withCommand("start-dev", "--features=organization");
        // KC 26 exposes /health/ready on the management port (9000), not the user-facing 8080.
        waitingFor(Wait.forHttp("/health/ready").forPort(9000).withStartupTimeout(Duration.ofMinutes(3)));
    }

    /** Mount a directory of provider JARs into {@code /opt/keycloak/providers}. */
    public PhaseTwoKeycloakContainer withProvidersDir(Path dir) {
        addFileSystemBind(dir.toAbsolutePath().toString(), "/opt/keycloak/providers", BindMode.READ_ONLY);
        return self();
    }

    public String baseUrl() {
        return "http://" + getHost() + ":" + getMappedPort(8080);
    }

    /**
     * Run {@code kcadm.sh} commands inside the container — used to disable {@code sslRequired} on
     * the master realm before the host can authenticate over plain HTTP.
     */
    public void disableMasterSsl() {
        try {
            org.testcontainers.containers.Container.ExecResult login = execInContainer(
                    "/opt/keycloak/bin/kcadm.sh",
                    "config", "credentials",
                    "--server", "http://localhost:8080",
                    "--realm", "master",
                    "--user", "admin",
                    "--password", "admin");
            if (login.getExitCode() != 0) {
                throw new IllegalStateException("kcadm login failed: " + login.getStderr());
            }
            org.testcontainers.containers.Container.ExecResult update = execInContainer(
                    "/opt/keycloak/bin/kcadm.sh",
                    "update", "realms/master",
                    "-s", "sslRequired=NONE");
            if (update.getExitCode() != 0) {
                throw new IllegalStateException("kcadm update failed: " + update.getStderr());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}

