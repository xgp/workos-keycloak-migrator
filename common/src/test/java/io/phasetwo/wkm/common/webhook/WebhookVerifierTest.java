package io.phasetwo.wkm.common.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookVerifierTest {

    private static final String SECRET = "whsec_super_secret_value";

    @Test
    void acceptsValidSignature() {
        long t = 1_700_000_000_000L;
        byte[] body = "{\"event\":\"user.created\"}".getBytes(StandardCharsets.UTF_8);
        String v1 = WebhookVerifier.hmacSha256Hex(SECRET, t + "." + new String(body, StandardCharsets.UTF_8));
        WebhookVerifier verifier = new WebhookVerifier(60_000L);
        boolean ok = verifier.verify("t=" + t + ",v1=" + v1, body, SECRET, t + 1000L);
        assertThat(ok).isTrue();
    }

    @Test
    void rejectsExpiredTimestamp() {
        long t = 1_700_000_000_000L;
        byte[] body = "x".getBytes();
        String v1 = WebhookVerifier.hmacSha256Hex(SECRET, t + "." + "x");
        WebhookVerifier verifier = new WebhookVerifier(1_000L);
        boolean ok = verifier.verify("t=" + t + ",v1=" + v1, body, SECRET, t + 600_000L);
        assertThat(ok).isFalse();
    }

    @Test
    void rejectsTamperedBody() {
        long t = 1_700_000_000_000L;
        String v1 = WebhookVerifier.hmacSha256Hex(SECRET, t + ".x");
        WebhookVerifier verifier = new WebhookVerifier(60_000L);
        boolean ok = verifier.verify("t=" + t + ",v1=" + v1, "y".getBytes(), SECRET, t);
        assertThat(ok).isFalse();
    }

    @Test
    void rejectsWrongSecret() {
        long t = 1_700_000_000_000L;
        String v1 = WebhookVerifier.hmacSha256Hex(SECRET, t + ".x");
        WebhookVerifier verifier = new WebhookVerifier(60_000L);
        boolean ok = verifier.verify("t=" + t + ",v1=" + v1, "x".getBytes(), "wrong", t);
        assertThat(ok).isFalse();
    }

    @Test
    void rejectsMissingHeader() {
        WebhookVerifier verifier = new WebhookVerifier();
        assertThat(verifier.verify(null, "x".getBytes(), SECRET)).isFalse();
        assertThat(verifier.verify("garbage", "x".getBytes(), SECRET)).isFalse();
    }
}
