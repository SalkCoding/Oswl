package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.security.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

@DisplayName("EncryptionService unit tests")
class EncryptionServiceTest {

    private static final String VALID_KEY = "dGVzdC1rZXktMzItYnl0ZXMtZm9yLXRlc3QtMDEyMzQ=";

    private EncryptionService encryption;

    @BeforeEach
    void setUp() {
        encryption = new EncryptionService();
        ReflectionTestUtils.setField(encryption, "encryptionKeyBase64", VALID_KEY);
        encryption.init();
    }

    @Test
    @DisplayName("encrypt then decrypt returns original plaintext")
    void encryptDecrypt_roundtrip() {
        String plain = "my-vcs-access-token";
        assertThat(encryption.decrypt(encryption.encrypt(plain))).isEqualTo(plain);
    }

    @Test
    @DisplayName("each encrypt call produces a different ciphertext (random IV)")
    void encrypt_producesUniqueOutputEachTime() {
        assertThat(encryption.encrypt("same")).isNotEqualTo(encryption.encrypt("same"));
    }

    @Test
    @DisplayName("decrypting tampered ciphertext throws IllegalStateException")
    void decrypt_tamperedData_throws() {
        String cipher = encryption.encrypt("secret");
        String tampered = cipher.substring(0, cipher.length() - 4) + "XXXX";
        assertThatThrownBy(() -> encryption.decrypt(tampered))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("init with wrong key length (not 32 bytes) throws")
    void init_wrongKeyLength_throws() {
        EncryptionService svc = new EncryptionService();
        ReflectionTestUtils.setField(svc, "encryptionKeyBase64", "dGVzdC1rZXktMTYtYnl0ZXMAAAA=");
        assertThatThrownBy(svc::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32 bytes");
    }

    @Test
    @DisplayName("init with blank key throws")
    void init_blankKey_throws() {
        EncryptionService svc = new EncryptionService();
        ReflectionTestUtils.setField(svc, "encryptionKeyBase64", "");
        assertThatThrownBy(svc::init)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("oswl.encryption.key");
    }
}