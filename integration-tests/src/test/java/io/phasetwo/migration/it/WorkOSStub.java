package io.phasetwo.migration.it;

import static com.github.tomakehurst.wiremock.client.WireMock.*;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.testcontainers.Testcontainers;

/**
 * Wraps a {@link WireMockServer} listening on a random port and exposes the host port to
 * Testcontainers so containers can reach it at {@code host.testcontainers.internal}.
 *
 * <p>Fixture files live in {@code src/test/resources/workos-fixtures/} and are served verbatim via
 * the helper methods below.
 */
public class WorkOSStub implements AutoCloseable {

    private final WireMockServer server;

    public WorkOSStub() {
        this.server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        Testcontainers.exposeHostPorts(server.port());
        WireMock.configureFor("localhost", server.port());
    }

    /** URL the test JVM uses (e.g. for the migrator running in-process). */
    public String hostUrl() {
        return "http://localhost:" + server.port();
    }

    /** URL the Keycloak container should use. */
    public String containerUrl() {
        return "http://host.testcontainers.internal:" + server.port();
    }

    public int port() {
        return server.port();
    }

    public void reset() {
        server.resetAll();
    }

    public void stubJson(String path, String fixture) {
        stubFor(get(urlPathEqualTo(path)).willReturn(okJson(loadFixture(fixture))));
    }

    public void stubJsonQuery(String path, String fixture) {
        // Match the path regardless of query parameters.
        stubFor(get(urlPathEqualTo(path)).willReturn(okJson(loadFixture(fixture))));
    }

    /** Allows tests to add bespoke stubs directly. */
    public WireMockServer server() {
        return server;
    }

    public static String loadFixture(String name) {
        Path p = Paths.get("src/test/resources/workos-fixtures", name);
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new RuntimeException("cannot read fixture " + p.toAbsolutePath(), e);
        }
    }

    @Override
    public void close() {
        server.stop();
    }
}
