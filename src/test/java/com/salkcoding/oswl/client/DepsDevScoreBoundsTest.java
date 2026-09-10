package com.salkcoding.oswl.client;

import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class DepsDevScoreBoundsTest {
    @ParameterizedTest
    @CsvSource({"0,0", "10,10", "7.5,7.5", "-0.1,", "10.1,", "1e400,", "-1e400,", "null,"})
    void onlineAndOfflineScoresRespectTheSameBounds(String input, Double expected) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var online = new DepsDevClient();
        ReflectionTestUtils.setField(online, "restClient", builder.baseUrl("https://api.deps.dev").build());
        String id = "GHSA-fixture";
        server.expect(requestTo("https://api.deps.dev/v3/advisories/" + id))
                .andRespond(withSuccess("{\"advisoryKey\":{\"id\":\"" + id
                        + "\"},\"title\":\"Evidence\",\"aliases\":[\"CVE-2026-0001\"],\"cvss3Score\":" + input + "}",
                        MediaType.APPLICATION_JSON));
        var store = mock(AirgappedSnapshotService.class);
        Double storedScore = input.equals("null") ? null : Double.valueOf(input);
        when(store.findAdvisories(anyCollection())).thenReturn(Map.of(id,
                new AirgappedSnapshotService.SnapshotAdvisory(id, "Evidence", List.of("CVE-2026-0001"), storedScore, null)));
        for (var client : List.of(online, new DepsDevClient(store, true))) {
            var result = client.getAdvisoriesBatch(List.of(id)).getFirst();
            assertThat(result).isNotNull();
            assertThat(result.ghsaId()).isEqualTo(id);
            assertThat(result.aliases()).containsExactly("CVE-2026-0001");
            assertThat(result.cvss3Score()).isEqualTo(expected);
        }
        server.verify();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY})
    void nonFiniteStoredScoresCannotBecomeCurrentAssessments(double input) {
        var store = mock(AirgappedSnapshotService.class);
        when(store.findAdvisories(anyCollection())).thenReturn(Map.of("GHSA-fixture",
                new AirgappedSnapshotService.SnapshotAdvisory("GHSA-fixture", "Evidence", List.of(), input, null)));
        assertThat(new DepsDevClient(store, true).getAdvisoriesBatch(List.of("GHSA-fixture"))
                .getFirst().cvss3Score()).isNull();
    }
}
