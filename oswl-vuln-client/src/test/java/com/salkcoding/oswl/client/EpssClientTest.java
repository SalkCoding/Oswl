package com.salkcoding.oswl.client;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.FAST)
@DisplayName("EpssClient")
class EpssClientTest {

    private final EpssClient client = new EpssClient();

    @Test
    @DisplayName("empty CVE list returns empty map")
    void emptyList() {
        assertThat(client.fetchScores(List.of())).isEmpty();
    }

    @Test
    @DisplayName("non-CVE ids are ignored")
    void nonCveIds() {
        assertThat(client.fetchScores(List.of("GHSA-xxxx"))).isEmpty();
    }
}
