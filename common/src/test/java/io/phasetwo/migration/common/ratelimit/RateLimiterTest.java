package io.phasetwo.migration.common.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.bucket4j.Bandwidth;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimiterTest {

    @Test
    void acquireUnderCapacityIsImmediate() {
        Bandwidth limit = Bandwidth.builder().capacity(100).refillGreedy(100, Duration.ofSeconds(1)).build();
        RateLimiter rl = new RateLimiter(limit);
        long start = System.nanoTime();
        for (int i = 0; i < 10; i++) rl.acquire();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs).isLessThan(200);
    }

    @Test
    void acquireBlocksWhenExhausted() {
        Bandwidth limit = Bandwidth.builder().capacity(2).refillGreedy(2, Duration.ofMillis(200)).build();
        RateLimiter rl = new RateLimiter(limit);
        long start = System.nanoTime();
        // Consume both tokens
        rl.acquire();
        rl.acquire();
        // Third call must wait for refill
        rl.acquire();
        long elapsedMs = (System.nanoTime() - start) / 1_000_000L;
        assertThat(elapsedMs).isGreaterThanOrEqualTo(80);
    }
}
