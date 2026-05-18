package io.phasetwo.migration.common.workos;

import static org.assertj.core.api.Assertions.assertThat;

import io.phasetwo.migration.common.ratelimit.RateLimiter;
import io.phasetwo.migration.common.workos.model.Cursor;
import io.phasetwo.migration.common.workos.model.Page;
import io.phasetwo.migration.common.workos.model.WIdentity;
import io.phasetwo.migration.common.workos.model.WUser;
import io.phasetwo.migration.common.workos.model.WWebhookEndpoint;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import okhttp3.OkHttpClient;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkOSHttpClientTest {

  private MockWebServer server;
  private WorkOSHttpClient client;

  @BeforeEach
  void start() throws IOException {
    server = new MockWebServer();
    server.start();
    OkHttpClient http = new OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build();
    client =
        new WorkOSHttpClient(
            server.url("/").toString().replaceAll("/$", ""),
            "sk_test_dummy",
            http,
            new RateLimiter(),
            10);
  }

  @AfterEach
  void stop() throws IOException {
    server.shutdown();
  }

  @Test
  void listUsersHandlesPagination() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(
                """
                {"object":"list","data":[
                  {"id":"user_1","email":"a@x.com","email_verified":true}
                ],"list_metadata":{"before":null,"after":"user_1"}}
                """));
    Page<WUser> page = client.listUsers(Cursor.empty(), 10);
    assertThat(page.data()).hasSize(1);
    assertThat(page.data().get(0).id()).isEqualTo("user_1");
    assertThat(page.listMetadata().after()).isEqualTo("user_1");

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("GET");
    assertThat(req.getHeader("Authorization")).isEqualTo("Bearer sk_test_dummy");
    assertThat(req.getPath()).contains("/user_management/users");
    assertThat(req.getPath()).contains("limit=10");
  }

  @Test
  void getOptionalReturnsEmptyOn404() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(404));
    Optional<WUser> result = client.getUser("user_missing");
    assertThat(result).isEmpty();
  }

  @Test
  void listUserIdentitiesParsesArray() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(
                """
                [
                  {"idp_id":"abc","type":"OAuth","provider":"GoogleOAuth"},
                  {"idp_id":"xyz","type":"OAuth","provider":"MicrosoftOAuth"}
                ]
                """));
    List<WIdentity> ids = client.listUserIdentities("user_1");
    assertThat(ids).hasSize(2);
    assertThat(ids.get(0).provider()).isEqualTo("GoogleOAuth");
    assertThat(ids.get(1).idpId()).isEqualTo("xyz");
  }

  @Test
  void retriesOn429() throws Exception {
    server.enqueue(new MockResponse().setResponseCode(429));
    server.enqueue(
        new MockResponse()
            .setResponseCode(200)
            .setBody(
                """
                {"object":"list","data":[],"list_metadata":{"before":null,"after":null}}
                """));
    Page<WUser> page = client.listUsers(Cursor.empty(), 10);
    assertThat(page.data()).isEmpty();
    assertThat(server.getRequestCount()).isEqualTo(2);
  }

  @Test
  void createWebhookEndpointSendsJsonBody() throws Exception {
    server.enqueue(
        new MockResponse()
            .setResponseCode(201)
            .setBody(
                """
                {"object":"webhook_endpoint","id":"we_1","endpoint_url":"https://example.com/x",
                 "secret":"shh","status":"enabled","events":["user.created"],
                 "created_at":"2026-01-01T00:00:00Z","updated_at":"2026-01-01T00:00:00Z"}
                """));
    WWebhookEndpoint ep =
        client.createWebhookEndpoint("https://example.com/x", List.of("user.created"));
    assertThat(ep.id()).isEqualTo("we_1");
    assertThat(ep.secret()).isEqualTo("shh");

    RecordedRequest req = server.takeRequest();
    assertThat(req.getMethod()).isEqualTo("POST");
    assertThat(req.getBody().readUtf8()).contains("user.created");
  }
}
