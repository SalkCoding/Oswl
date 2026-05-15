package com.salkcoding.oswl.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM symmetric encryption for sensitive session values (e.g. GitHub PATs).
 *
 * Key is read from the {@code OSWL_ENCRYPTION_KEY} environment variable (Base64-encoded 32 bytes).
 * If the key is not configured the service operates in pass-through mode and logs a warning;
 * this is safe for local development but must not be used in production.
 *
 * Cipher format: Base64( IV[12] || ciphertext[n] || tag[16] )
 */
@Slf4j
@Service
public class SessionCipherService {

    private static final String ALGORITHM   = "AES/GCM/NoPadding";
    private static final int    IV_BYTES    = 12;
    private static final int    TAG_BITS    = 128;

    private final SecretKey    secretKey;
    private final boolean      enabled;
    private final SecureRandom random = new SecureRandom();

    public SessionCipherService(@Value("${oswl.encryption.key:}") String base64Key) {
        if (base64Key == null || base64Key.isBlank()) {
            log.warn("[SessionCipher] OSWL_ENCRYPTION_KEY is not set — PAT encryption is DISABLED. "
                    + "Set this key in production!");
            this.secretKey = null;
            this.enabled   = false;
        } else {
            byte[] keyBytes = Base64.getDecoder().decode(base64Key.trim());
            if (keyBytes.length != 32) {
                throw new IllegalArgumentException(
                        "OSWL_ENCRYPTION_KEY must be a Base64-encoded 256-bit (32 byte) key, "
                        + "but got " + keyBytes.length + " bytes.");
            }
            this.secretKey = new SecretKeySpec(keyBytes, "AES");
            this.enabled   = true;
            log.info("[SessionCipher] PAT encryption enabled (AES-256-GCM).");
        }
    }

    /**
     * Encrypts {@code plaintext} and returns a Base64-encoded ciphertext blob.
     * Returns {@code plaintext} unchanged when encryption is disabled.
     */
    public String encrypt(String plaintext) {
        if (!enabled) return plaintext;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(java.nio.charset.StandardCharsets.UTF_8));

            byte[] blob = new byte[IV_BYTES + ciphertext.length];
            System.arraycopy(iv,         0, blob, 0,       IV_BYTES);
            System.arraycopy(ciphertext, 0, blob, IV_BYTES, ciphertext.length);
            return Base64.getEncoder().encodeToString(blob);
        } catch (Exception e) {
            throw new IllegalStateException("PAT encryption failed", e);
        }
    }

    /**
     * Decrypts a Base64-encoded ciphertext blob produced by {@link #encrypt}.
     * Returns {@code cipherBlob} unchanged when encryption is disabled.
     */
    public String decrypt(String cipherBlob) {
        if (!enabled) return cipherBlob;
        try {
            byte[] blob = Base64.getDecoder().decode(cipherBlob);
            byte[] iv   = new byte[IV_BYTES];
            System.arraycopy(blob, 0, iv, 0, IV_BYTES);
            byte[] ciphertext = new byte[blob.length - IV_BYTES];
            System.arraycopy(blob, IV_BYTES, ciphertext, 0, ciphertext.length);

            Cipher cipher = Cipher.getInstance(ALGORITHM);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_BITS, iv));
            byte[] plain = cipher.doFinal(ciphertext);
            return new String(plain, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("PAT decryption failed", e);
        }
    }

    public boolean isEnabled() {
        return enabled;
    }
}
