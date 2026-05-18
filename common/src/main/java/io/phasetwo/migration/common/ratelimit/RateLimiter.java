package io.phasetwo.migration.common.ratelimit;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Token-bucket limiter honouring WorkOS rate limits:
 *
 * <ul>
 *   <li>Global: 6,000 requests / 60 s.
 *   <li>{@code /directory_users}: 4 / 1 s.
 *   <li>{@code DELETE /organizations/{id}}: 50 / 60 s.
 *   <li>AuthKit authentication endpoints: 10 / 60 s per email.
 * </ul>
 *
 * <p>Per the WorkOS docs, response headers about remaining quota are not guaranteed; we rely on
 * client-side metering plus on-429 backoff at the HTTP layer.
 */
public final class RateLimiter {

    private final Bucket global;
    private final Map<String, Bucket> perKey = new ConcurrentHashMap<>();

    public RateLimiter() {
        this(
                Bandwidth.builder()
                        .capacity(6000)
                        .refillGreedy(6000, Duration.ofSeconds(60))
                        .build());
    }

    RateLimiter(Bandwidth global) {
        this.global = Bucket.builder().addLimit(global).build();
    }

    public void acquire() {
        try {
            global.asBlocking().consume(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
    }

    public void acquire(String bucketKey, Bandwidth limit) {
        Bucket b =
                perKey.computeIfAbsent(bucketKey, k -> Bucket.builder().addLimit(limit).build());
        try {
            b.asBlocking().consume(1);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException(e);
        }
        acquire();
    }

    public static Bandwidth directoryUsersLimit() {
        return Bandwidth.builder().capacity(4).refillGreedy(4, Duration.ofSeconds(1)).build();
    }

    public static Bandwidth organizationDeleteLimit() {
        return Bandwidth.builder().capacity(50).refillGreedy(50, Duration.ofSeconds(60)).build();
    }

    public static Bandwidth authKitAuthenticateLimit() {
        return Bandwidth.builder().capacity(10).refillGreedy(10, Duration.ofSeconds(60)).build();
    }
}
