package io.phasetwo.migration.common.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashes {

  public static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 unavailable", e);
    }
  }

  /** First 16 hex chars of SHA-256 — used to fingerprint an API key without exposing it. */
  public static String fingerprint(String secret) {
    return sha256Hex(secret).substring(0, 16);
  }

  private Hashes() {}
}
