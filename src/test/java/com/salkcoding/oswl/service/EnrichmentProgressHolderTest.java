package com.salkcoding.oswl.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("EnrichmentProgressHolder 단위 테스트")
class EnrichmentProgressHolderTest {

    private final EnrichmentProgressHolder holder = new EnrichmentProgressHolder();

    @Test
    @DisplayName("set/get: 저장한 메시지를 그대로 반환한다")
    void setAndGet_returnsStoredMessage() {
        holder.set(1L, "Processing...");

        assertThat(holder.get(1L)).isEqualTo("Processing...");
    }

    @Test
    @DisplayName("get: 등록되지 않은 ID는 null을 반환한다")
    void get_unknownId_returnsNull() {
        assertThat(holder.get(999L)).isNull();
    }

    @Test
    @DisplayName("remove: 삭제 후 get은 null을 반환한다")
    void remove_thenGetReturnsNull() {
        holder.set(2L, "Enriching");
        holder.remove(2L);

        assertThat(holder.get(2L)).isNull();
    }

    @Test
    @DisplayName("remove: 존재하지 않는 ID 삭제 시 예외가 발생하지 않는다")
    void remove_nonExistentId_noException() {
        // should not throw
        holder.remove(Long.MAX_VALUE);
    }

    @Test
    @DisplayName("set: 동일 ID에 대해 메시지를 덮어쓴다")
    void set_overwritesExistingMessage() {
        holder.set(3L, "First");
        holder.set(3L, "Second");

        assertThat(holder.get(3L)).isEqualTo("Second");
    }

    @Test
    @DisplayName("set/get: 여러 ID를 독립적으로 관리한다")
    void set_multipleIds_areIndependent() {
        holder.set(10L, "A");
        holder.set(20L, "B");
        holder.set(30L, "C");

        assertThat(holder.get(10L)).isEqualTo("A");
        assertThat(holder.get(20L)).isEqualTo("B");
        assertThat(holder.get(30L)).isEqualTo("C");
    }
}
