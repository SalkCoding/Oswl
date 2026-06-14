package com.salkcoding.oswl.service;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag(TestTags.FAST)
@DisplayName("SessionCipherService 단위 테스트")
class SessionCipherServiceTest {

    private static final String VALID_KEY =
            Base64.getEncoder().encodeToString(new byte[32]); // 32 bytes → 44 chars base64

    // ── disabled mode ─────────────────────────────────────────────────────

    @Test
    @DisplayName("isEnabled: 키가 비어있으면 false")
    void isEnabled_blankKey_false() {
        SessionCipherService svc = new SessionCipherService("");
        assertThat(svc.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("encrypt: 비활성화 상태이면 원문을 그대로 반환한다")
    void encrypt_disabled_passthrough() {
        SessionCipherService svc = new SessionCipherService("");
        assertThat(svc.encrypt("hello")).isEqualTo("hello");
    }

    @Test
    @DisplayName("decrypt: 비활성화 상태이면 입력을 그대로 반환한다")
    void decrypt_disabled_passthrough() {
        SessionCipherService svc = new SessionCipherService("");
        assertThat(svc.decrypt("hello")).isEqualTo("hello");
    }

    // ── enabled mode ──────────────────────────────────────────────────────

    @Test
    @DisplayName("isEnabled: 유효한 32바이트 키이면 true")
    void isEnabled_validKey_true() {
        SessionCipherService svc = new SessionCipherService(VALID_KEY);
        assertThat(svc.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("encrypt → decrypt: 암호화 후 복호화하면 원문과 같다")
    void encryptDecrypt_roundTrip() {
        SessionCipherService svc = new SessionCipherService(VALID_KEY);
        String plaintext = "secret-token-12345";
        String ciphertext = svc.encrypt(plaintext);

        assertThat(ciphertext).isNotEqualTo(plaintext);
        assertThat(svc.decrypt(ciphertext)).isEqualTo(plaintext);
    }

    @Test
    @DisplayName("encrypt: 같은 평문을 두 번 암호화하면 다른 결과 (랜덤 IV)")
    void encrypt_sameInput_differentOutput() {
        SessionCipherService svc = new SessionCipherService(VALID_KEY);
        String a = svc.encrypt("sameValue");
        String b = svc.encrypt("sameValue");
        assertThat(a).isNotEqualTo(b);
    }

    // ── wrong key length ─────────────────────────────────────────────────

    @Test
    @DisplayName("생성자: 32바이트가 아닌 키는 IllegalArgumentException을 던진다")
    void constructor_wrongKeyLength_throws() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]); // 16 bytes
        assertThatThrownBy(() -> new SessionCipherService(shortKey))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
