package com.novelagent.platform.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Shared, deterministic SHA-256 encoding for source fingerprints and request snapshots.
 * Does not trim text, normalize line endings or serialize JSON: callers define the exact basis.
 */
public final class Sha256 {
    private Sha256() {
    }

    public static String ofUtf8(String value) {
        return ofBytes(Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8));
    }

    public static String ofBytes(byte[] value) {
        Objects.requireNonNull(value, "value");
        try {
            // MessageDigest is mutable; each call owns its instance, including concurrent requests.
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
