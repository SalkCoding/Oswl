package com.salkcoding.oswl.client;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EpssClientTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void normalizesValidIdentitiesAndNeverQueriesMalformedOnes(boolean offline) {
        var ids = java.util.Arrays.asList(null, "", "CVE-2026-1", "CVE-2026-1000&limit=999", "GHSA-test",
                " cve-2026-1000 ", "CVE-2026-1000", "CVE-2026-1234567");
        var expectedIds = List.of("CVE-2026-1000", "CVE-2026-1234567");
        var scores = java.util.Map.of("CVE-2026-1000", 0.0, "CVE-2026-1234567", 0.5);
        var snapshot = org.mockito.Mockito.mock(com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.class);
        org.mockito.Mockito.when(snapshot.readEpssSnapshot(expectedIds)).thenReturn(scores);
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        if (!offline) server.expect(requestTo("https://api.first.org/data/v1/epss?cve=CVE-2026-1000,CVE-2026-1234567"))
                .andRespond(withSuccess("{\"data\":[{\"cve\":\"CVE-2026-1000\",\"epss\":\"0\"},{\"cve\":\"CVE-2026-1234567\",\"epss\":\"0.5\"}]}", MediaType.APPLICATION_JSON));
        var client = new EpssClient(snapshot, offline);
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        assertThat(client.fetchScores(ids)).isEqualTo(scores);
        if (offline) org.mockito.Mockito.verify(snapshot).readEpssSnapshot(expectedIds);
        else org.mockito.Mockito.verifyNoInteractions(snapshot);
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void queriesEveryBatchAndPreservesLaterResultsAfterFailure(boolean firstFails) throws Exception {
        var ids = java.util.stream.IntStream.range(1000, 1051).mapToObj(i -> "CVE-2026-" + i).toList();
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new EpssClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (int start = 0; start < ids.size(); start += 50) {
            var batch = ids.subList(start, Math.min(start + 50, ids.size()));
            String response = mapper.writeValueAsString(java.util.Map.of("data", batch.stream()
                    .map(id -> java.util.Map.of("cve", id, "epss", "0.5")).toList()));
            server.expect(requestTo("https://api.first.org/data/v1/epss?cve=" + String.join(",", batch)))
                    .andRespond(start == 0 && firstFails ? org.springframework.test.web.client.response.MockRestResponseCreators.withServerError()
                            : withSuccess(response, MediaType.APPLICATION_JSON));
        }
        var result = client.fetchScores(ids);
        assertThat(result).hasSize(firstFails ? 1 : 51).containsEntry(ids.getLast(), 0.5);
        server.verify();
    }

    @org.junit.jupiter.api.Test
    void offlineQueriesAreNotTruncatedToHttpBatchSize() {
        var ids = java.util.stream.IntStream.range(1000, 1051).mapToObj(i -> "CVE-2026-" + i).toList();
        var snapshot = org.mockito.Mockito.mock(com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.class);
        org.mockito.Mockito.when(snapshot.readEpssSnapshot(ids)).thenCallRealMethod();
        org.mockito.Mockito.when(snapshot.findEpssScores(ids)).thenReturn(java.util.Map.of(ids.getLast(), 0.5));
        assertThat(new EpssClient(snapshot, true).fetchScores(ids)).containsEntry(ids.getLast(), 0.5);
        org.mockito.Mockito.verify(snapshot).findEpssScores(ids);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0.2,0.8", "0.8,0.2", "NaN,0.8", "0.8,NaN", "0.2,0.2"})
    void conflictingDuplicatesStayUnresolvedAndUnrequestedIdsAreExcluded(String first, String second) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new EpssClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(requestTo("https://api.first.org/data/v1/epss?cve=CVE-2026-0001"))
                .andRespond(withSuccess("{\"data\":[{\"cve\":\"CVE-2026-0001\",\"epss\":\"" + first
                        + "\"},{\"cve\":\"CVE-2026-0001\",\"epss\":\"" + second
                        + "\"},{\"cve\":\"CVE-2026-0001\",\"epss\":\"0.2\"},"
                        + "{\"cve\":\"CVE-2026-9999\",\"epss\":\"0.9\"}]}", MediaType.APPLICATION_JSON));
        var result = client.fetchScores(List.of("CVE-2026-0001"));
        if (first.equals(second)) assertThat(result).containsOnly(org.assertj.core.api.Assertions.entry("CVE-2026-0001", 0.2));
        else assertThat(result).isEmpty();
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-Infinity", "-0.1", "1.1", "broken", "0", "0.5", "1"})
    void validatesScoresWhilePreservingOtherRows(String score) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new EpssClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(requestTo("https://api.first.org/data/v1/epss?cve=CVE-2026-0001,CVE-2026-0002"))
                .andRespond(withSuccess("{\"data\":[{\"cve\":\"CVE-2026-0001\",\"epss\":\"" + score
                        + "\"},{\"cve\":\"CVE-2026-0002\",\"epss\":\"0.5\"}]}", MediaType.APPLICATION_JSON));
        var expected = new java.util.LinkedHashMap<String, Double>();
        expected.put("CVE-2026-0002", 0.5);
        if (List.of("0", "0.5", "1").contains(score)) expected.put("CVE-2026-0001", Double.parseDouble(score));
        assertThat(client.fetchScores(List.of("CVE-2026-0001", "CVE-2026-0002"))).isEqualTo(expected);
        server.verify();
    }
}
