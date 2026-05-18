package io.phasetwo.migration.common.webhook;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Verifies the WorkOS {@code WorkOS-Signature: t=<millis>,v1=<hex>} header.
 *
 * <p>Signed content: {@code timestamp + "." + rawBody}, HMAC-SHA256 keyed with the webhook secret;
 * encoded as hex.
 */
public final class WebhookVerifier {

  private final long toleranceMillis;

  public WebhookVerifier() {
    this(300_000L);
  }

  public WebhookVerifier(long toleranceMillis) {
    this.toleranceMillis = toleranceMillis;
  }

  public boolean verify(String header, byte[] body, String secret) {
    return verify(header, body, secret, Instant.now().toEpochMilli());
  }

  /** Visible for unit testing. */
  public boolean verify(String header, byte[] body, String secret, long nowMillis) {
    if (header == null || secret == null) return false;
    Long t = null;
    String v1 = null;
    for (String part : header.split(",")) {
      String p = part.trim();
      int eq = p.indexOf('=');
      if (eq < 0) continue;
      String k = p.substring(0, eq);
      String v = p.substring(eq + 1);
      if (k.equals("t")) {
        try {
          t = Long.parseLong(v);
        } catch (NumberFormatException ignored) {
        }
      } else if (k.equals("v1")) {
        v1 = v;
      }
    }
    if (t == null || v1 == null) return false;
    if (Math.abs(nowMillis - t) > toleranceMillis) return false;

    String signedPayload = t + "." + new String(body, StandardCharsets.UTF_8);
    String computed = hmacSha256Hex(secret, signedPayload);
    return MessageDigest.isEqual(
        computed.getBytes(StandardCharsets.US_ASCII), v1.getBytes(StandardCharsets.US_ASCII));
  }

  public static String hmacSha256Hex(String key, String content) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      byte[] raw = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(raw);
    } catch (Exception e) {
      throw new IllegalStateException("HMAC-SHA256 unavailable", e);
    }
  }
}
