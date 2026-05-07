package com.salkcoding.oswl.auth.security;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * AES-256-GCM symmetric encryption for storing sensitive secrets like VCS tokens.
 * Format: Base64( IV(12) || ciphertext )
 */
@Service
public class EncryptionService {

    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    @Value("${oswl.encryption.key:}")
    private String encryptionKeyBase64;

    private SecretKey secretKey;
    private final SecureRandom secureRandom = new SecureRandom();

    @PostConstruct
    public void init() {
        byte[] keyBytes;
        if (encryptionKeyBase64 == null || encryptionKeyBase64.isBlank()) {
            // Dev fallback — generates an ephemeral key per app start.
            // In production, oswl.encryption.key MUST be set via env/config.
            keyBytes = new byte[32];
            secureRandom.nextBytes(keyBytes);
        } else {
            keyBytes = Base64.getDecoder().decode(encryptionKeyBase64);
            if (keyBytes.length != 32) {
                throw new IllegalStateException("oswl.encryption.key must decode to 32 bytes (AES-256)");
            }
        }
        this.secretKey = new SecretKeySpec(keyBytes, "AES");
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("Encryption failed", e);
        }
    }

    public String decrypt(String ciphertextBase64) {
        try {
            byte[] data = Base64.getDecoder().decode(ciphertextBase64);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(data, 0, iv, 0, IV_LENGTH);
            byte[] ct = new byte[data.length - IV_LENGTH];
            System.arraycopy(data, IV_LENGTH, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Decryption failed", e);
        }
    }
}
