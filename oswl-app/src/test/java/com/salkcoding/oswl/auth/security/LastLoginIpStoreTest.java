package com.salkcoding.oswl.auth.security;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("LastLoginIpStore 단위 테스트")
class LastLoginIpStoreTest {

    private final LastLoginIpStore store = new LastLoginIpStore();

    @Test
    @DisplayName("put/get: 저장한 IP를 그대로 반환한다")
    void putAndGet_returnsStoredIp() {
        store.put("user@example.com", "192.168.1.1");

        assertThat(store.get("user@example.com")).isEqualTo("192.168.1.1");
    }

    @Test
    @DisplayName("get: 등록되지 않은 이메일은 null을 반환한다")
    void get_unknownEmail_returnsNull() {
        assertThat(store.get("unknown@example.com")).isNull();
    }

    @Test
    @DisplayName("put: 동일 이메일에 새 IP를 저장하면 덮어쓴다")
    void put_overwritesPreviousIp() {
        store.put("admin@example.com", "10.0.0.1");
        store.put("admin@example.com", "10.0.0.2");

        assertThat(store.get("admin@example.com")).isEqualTo("10.0.0.2");
    }

    @Test
    @DisplayName("put: 여러 사용자의 IP를 독립적으로 관리한다")
    void put_multipleUsers_areIndependent() {
        store.put("alice@example.com", "1.1.1.1");
        store.put("bob@example.com", "2.2.2.2");

        assertThat(store.get("alice@example.com")).isEqualTo("1.1.1.1");
        assertThat(store.get("bob@example.com")).isEqualTo("2.2.2.2");
    }
}
