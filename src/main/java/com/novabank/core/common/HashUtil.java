package com.novabank.core.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Shared SHA-256 hex-digest helper. Extracted from what was previously private, near-identical
 * logic duplicated in {@code TransactionCommandService} (idempotency request hashing) — now also
 * used by {@code RefreshTokenService} to hash opaque refresh tokens before persisting them, so a
 * database read/backup leak never exposes a usable refresh token (only its hash, from which the
 * original cannot be recovered).
 */
public final class HashUtil {

    private HashUtil() {
    }

    public static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
