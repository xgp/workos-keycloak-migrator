package io.phasetwo.migration.common.workos;

import lombok.extern.jbosslog.JBossLog;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.phasetwo.migration.common.ratelimit.RateLimiter;
import io.phasetwo.migration.common.util.JsonUtil;
import io.phasetwo.migration.common.workos.model.Cursor;
import io.phasetwo.migration.common.workos.model.Page;
import io.phasetwo.migration.common.workos.model.WConnection;
import io.phasetwo.migration.common.workos.model.WDirectory;
import io.phasetwo.migration.common.workos.model.WDirectoryGroup;
import io.phasetwo.migration.common.workos.model.WDirectoryUser;
import io.phasetwo.migration.common.workos.model.WIdentity;
import io.phasetwo.migration.common.workos.model.WOrgMembership;
import io.phasetwo.migration.common.workos.model.WOrganization;
import io.phasetwo.migration.common.workos.model.WRole;
import io.phasetwo.migration.common.workos.model.WUser;
import io.phasetwo.migration.common.workos.model.WWebhookEndpoint;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
/**
 * OkHttp + Jackson backed {@link WorkOSClient}. Honours {@link RateLimiter} for every outbound
 * request and retries idempotent reads with exponential backoff on 429 / 5xx.
 */
@JBossLog
public class WorkOSHttpClient implements WorkOSClient {
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    private static final MediaType FORM = MediaType.get("application/x-www-form-urlencoded");

    private final String baseUrl;
    private final String apiKey;
    private final OkHttpClient http;
    private final ObjectMapper mapper;
    private final RateLimiter rateLimiter;
    private final int defaultPageSize;

    public WorkOSHttpClient(String baseUrl, String apiKey) {
        this(baseUrl, apiKey, defaultClient(), new RateLimiter(), 100);
    }

    public WorkOSHttpClient(
            String baseUrl,
            String apiKey,
            OkHttpClient http,
            RateLimiter rateLimiter,
            int defaultPageSize) {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey;
        this.http = http;
        this.rateLimiter = rateLimiter;
        this.mapper = JsonUtil.mapper();
        this.defaultPageSize = defaultPageSize;
    }

    private static OkHttpClient defaultClient() {
        return new OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    // ------------- public API -------------

    @Override
    public Page<WUser> listUsers(Cursor cursor, int limit) {
        return getPage("/user_management/users", cursor, limit, new TypeReference<>() {});
    }

    @Override
    public Optional<WUser> findUserByEmail(String email) {
        HttpUrl.Builder b = httpUrl("/user_management/users").newBuilder().addQueryParameter("email", email).addQueryParameter("limit", "1");
        Page<WUser> page = doGet(b.build(), new TypeReference<>() {});
        return page.data().stream().findFirst();
    }

    @Override
    public Optional<WUser> getUser(String id) {
        return getOptional("/user_management/users/" + id, WUser.class);
    }

    @Override
    public List<WIdentity> listUserIdentities(String userId) {
        HttpUrl url = httpUrl("/user_management/users/" + userId + "/identities");
        return doGet(url, new TypeReference<>() {});
    }

    @Override
    public Page<WOrganization> listOrganizations(Cursor cursor, int limit) {
        return getPage("/organizations", cursor, limit, new TypeReference<>() {});
    }

    @Override
    public Optional<WOrganization> getOrganization(String id) {
        return getOptional("/organizations/" + id, WOrganization.class);
    }

    @Override
    public Page<WOrgMembership> listOrganizationMemberships(String organizationId, Cursor cursor, int limit) {
        HttpUrl.Builder b = httpUrl("/user_management/organization_memberships").newBuilder()
                .addQueryParameter("organization_id", organizationId)
                .addQueryParameter("limit", Integer.toString(limit > 0 ? limit : defaultPageSize));
        if (cursor != null && cursor.after() != null) b.addQueryParameter("after", cursor.after());
        if (cursor != null && cursor.before() != null) b.addQueryParameter("before", cursor.before());
        return doGet(b.build(), new TypeReference<>() {});
    }

    @Override
    public Optional<WOrgMembership> getOrganizationMembership(String id) {
        return getOptional(
                "/user_management/organization_memberships/" + id, WOrgMembership.class);
    }

    @Override
    public List<WRole> listEnvironmentRoles() {
        HttpUrl url = httpUrl("/authorization/roles");
        return doGet(url, new TypeReference<Page<WRole>>() {}).data();
    }

    @Override
    public List<WRole> listOrganizationRoles(String organizationId) {
        HttpUrl url = httpUrl("/authorization/organizations/" + organizationId + "/roles");
        return doGet(url, new TypeReference<Page<WRole>>() {}).data();
    }

    @Override
    public Page<WConnection> listConnections(Cursor cursor, int limit) {
        return getPage("/connections", cursor, limit, new TypeReference<>() {});
    }

    @Override
    public Optional<WConnection> getConnection(String id) {
        return getOptional("/connections/" + id, WConnection.class);
    }

    @Override
    public Page<WDirectory> listDirectories(Cursor cursor, int limit) {
        return getPage("/directories", cursor, limit, new TypeReference<>() {});
    }

    @Override
    public Optional<WDirectory> getDirectory(String id) {
        return getOptional("/directories/" + id, WDirectory.class);
    }

    @Override
    public Page<WDirectoryGroup> listDirectoryGroups(String directoryId, Cursor cursor, int limit) {
        HttpUrl.Builder b = httpUrl("/directory_groups").newBuilder()
                .addQueryParameter("directory", directoryId)
                .addQueryParameter("limit", Integer.toString(limit > 0 ? limit : defaultPageSize));
        if (cursor != null && cursor.after() != null) b.addQueryParameter("after", cursor.after());
        if (cursor != null && cursor.before() != null) b.addQueryParameter("before", cursor.before());
        return doGet(b.build(), new TypeReference<>() {});
    }

    @Override
    public Page<WDirectoryUser> listDirectoryUsers(String directoryId, Cursor cursor, int limit) {
        HttpUrl.Builder b = httpUrl("/directory_users").newBuilder()
                .addQueryParameter("directory", directoryId)
                .addQueryParameter("limit", Integer.toString(limit > 0 ? limit : defaultPageSize));
        if (cursor != null && cursor.after() != null) b.addQueryParameter("after", cursor.after());
        if (cursor != null && cursor.before() != null) b.addQueryParameter("before", cursor.before());
        return doGet(b.build(), new TypeReference<>() {});
    }

    @Override
    public Optional<WDirectoryUser> findDirectoryUserByEmail(String email) {
        HttpUrl url = httpUrl("/directory_users").newBuilder()
                .addQueryParameter("email", email)
                .addQueryParameter("limit", "1")
                .build();
        Page<WDirectoryUser> page = doGet(url, new TypeReference<>() {});
        return page.data().stream().findFirst();
    }

    @Override
    public List<WWebhookEndpoint> listWebhookEndpoints() {
        Page<WWebhookEndpoint> page = doGet(httpUrl("/webhook_endpoints"), new TypeReference<>() {});
        return page.data();
    }

    @Override
    public WWebhookEndpoint createWebhookEndpoint(String endpointUrl, List<String> events) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("endpoint_url", endpointUrl);
        body.put("events", events);
        return postJson(httpUrl("/webhook_endpoints"), body, WWebhookEndpoint.class);
    }

    @Override
    public WWebhookEndpoint updateWebhookEndpoint(String id, List<String> events) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("events", events);
        return patchJson(httpUrl("/webhook_endpoints/" + id), body, WWebhookEndpoint.class);
    }

    @Override
    public boolean authenticatePassword(
            String email, String password, String clientId, String clientSecret) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("client_id", clientId);
        body.put("client_secret", clientSecret);
        body.put("grant_type", "password");
        body.put("email", email);
        body.put("password", password);
        Request req = baseRequest(httpUrl("/user_management/authenticate"))
                .post(jsonBody(body))
                .build();
        try (Response resp = call(req)) {
            return resp.isSuccessful();
        }
    }

    // ------------- helpers -------------

    private HttpUrl httpUrl(String path) {
        return HttpUrl.parse(baseUrl + path);
    }

    private Request.Builder baseRequest(HttpUrl url) {
        return new Request.Builder()
                .url(url)
                .header("Authorization", "Bearer " + apiKey)
                .header("Accept", "application/json")
                .header("X-Request-Id", java.util.UUID.randomUUID().toString());
    }

    private RequestBody jsonBody(Object body) {
        try {
            return RequestBody.create(mapper.writeValueAsBytes(body), JSON);
        } catch (Exception e) {
            throw new WorkOSException("serializing json", e);
        }
    }

    private <T> Page<T> getPage(String path, Cursor cursor, int limit, TypeReference<Page<T>> typeRef) {
        HttpUrl.Builder b = httpUrl(path).newBuilder();
        b.addQueryParameter("limit", Integer.toString(limit > 0 ? limit : defaultPageSize));
        if (cursor != null && cursor.after() != null) b.addQueryParameter("after", cursor.after());
        if (cursor != null && cursor.before() != null) b.addQueryParameter("before", cursor.before());
        return doGet(b.build(), typeRef);
    }

    private <T> Optional<T> getOptional(String path, Class<T> cls) {
        Request req = baseRequest(httpUrl(path)).get().build();
        try (Response resp = call(req)) {
            if (resp.code() == 404) return Optional.empty();
            if (!resp.isSuccessful()) {
                throw new WorkOSException(resp.code(), "GET " + path + " failed: " + resp.code());
            }
            ResponseBody body = resp.body();
            if (body == null) return Optional.empty();
            return Optional.of(mapper.readValue(body.byteStream(), cls));
        } catch (IOException e) {
            throw new WorkOSException("io error on GET " + path, e);
        }
    }

    private <T> T doGet(HttpUrl url, TypeReference<T> typeRef) {
        Request req = baseRequest(url).get().build();
        try (Response resp = call(req)) {
            if (!resp.isSuccessful()) {
                throw new WorkOSException(resp.code(), "GET " + url + " failed: " + resp.code());
            }
            ResponseBody body = resp.body();
            if (body == null) throw new WorkOSException(resp.code(), "no body");
            return mapper.readValue(body.byteStream(), typeRef);
        } catch (IOException e) {
            throw new WorkOSException("io error on GET " + url, e);
        }
    }

    private <T> T postJson(HttpUrl url, Object body, Class<T> cls) {
        Request req = baseRequest(url).post(jsonBody(body)).build();
        try (Response resp = call(req)) {
            if (!resp.isSuccessful()) {
                throw new WorkOSException(resp.code(), "POST " + url + " failed: " + resp.code());
            }
            ResponseBody rb = resp.body();
            if (rb == null) throw new WorkOSException(resp.code(), "no body");
            return mapper.readValue(rb.byteStream(), cls);
        } catch (IOException e) {
            throw new WorkOSException("io error on POST " + url, e);
        }
    }

    private <T> T patchJson(HttpUrl url, Object body, Class<T> cls) {
        Request req = baseRequest(url).patch(jsonBody(body)).build();
        try (Response resp = call(req)) {
            if (!resp.isSuccessful()) {
                throw new WorkOSException(resp.code(), "PATCH " + url + " failed: " + resp.code());
            }
            ResponseBody rb = resp.body();
            if (rb == null) throw new WorkOSException(resp.code(), "no body");
            return mapper.readValue(rb.byteStream(), cls);
        } catch (IOException e) {
            throw new WorkOSException("io error on PATCH " + url, e);
        }
    }

    private Response call(Request request) {
        rateLimiter.acquire();
        int attempt = 0;
        long backoffMillis = 250L;
        Response resp = null;
        while (true) {
            try {
                resp = http.newCall(request).execute();
            } catch (IOException e) {
                if (++attempt >= 5) throw new WorkOSException("io error", e);
                sleep(backoffMillis);
                backoffMillis = Math.min(backoffMillis * 2, 5_000L);
                continue;
            }
            if (resp.code() == 429 || resp.code() >= 500) {
                if (++attempt >= 5) return resp;
                resp.close();
                String retryAfter = resp.header("Retry-After");
                long wait = backoffMillis;
                if (retryAfter != null) {
                    try { wait = Math.max(wait, Long.parseLong(retryAfter) * 1000L); } catch (NumberFormatException ignored) {}
                }
                log.debugf("retrying %s after %s ms (attempt %s, code %s)", request.url(), wait, attempt, resp.code());
                sleep(wait);
                backoffMillis = Math.min(backoffMillis * 2, 5_000L);
                continue;
            }
            return resp;
        }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    /** Visible for tests. */
    static String encode(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    /** Visible for tests; collects all pages of an entity. */
    public static <T> List<T> collect(java.util.function.Function<Cursor, Page<T>> fetcher) {
        List<T> out = new ArrayList<>();
        Cursor cursor = Cursor.empty();
        while (true) {
            Page<T> page = fetcher.apply(cursor);
            out.addAll(page.data());
            if (page.listMetadata() == null || page.listMetadata().after() == null) break;
            cursor = new Cursor(null, page.listMetadata().after());
        }
        return out;
    }
}
