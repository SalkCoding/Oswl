package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.client.OsvClient;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OsvFixVersionParityTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"6.0.0,false", "7.0.0,true", "8.0.3,true", "8.0.4,false", "8.0.5,false"})
    void microsoftNugetAdvisoryPreservesIntroducedAndFixedBoundaries(String installed, boolean affected) throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/advisories/GHSA-hh2w-p6rv-4g7w.json")) {
            assertThat(input).isNotNull();
            bytes = input.readAllBytes();
        }
        var document = mapper.readTree(bytes);
        var ranges = document.path("affected").get(0).path("ranges");
        assertThat(OsvRangeEvaluator.evaluate("NUGET", installed, null, ranges)).isEqualTo(affected
                ? OsvRangeEvaluator.Result.AFFECTED : OsvRangeEvaluator.Result.NOT_AFFECTED);
        assertThat(OsvFixVersionSelector.select(document, "NuGet", "System.Text.Json", installed).version())
                .isEqualTo(affected ? "8.0.4" : null);
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unresolved = new LinkedHashSet<>();
        ReflectionTestUtils.invokeMethod(new OsvBulkSource(mapper), "processVulnEntry", bytes, "NUGET",
                Map.of("System.Text.Json", Set.of(installed)), findings, unresolved);
        assertThat(findings.isEmpty()).isEqualTo(!affected);
        assertThat(unresolved).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"GHSA-wf6x-7x77-mvgw,npm,immutable,3.8.2,3.8.3", "GHSA-wf6x-7x77-mvgw,npm,immutable,4.3.6,4.3.8",
            "GHSA-wf6x-7x77-mvgw,npm,immutable,4.3.7,4.3.8", "GHSA-wf6x-7x77-mvgw,npm,immutable,5.1.4,5.1.5",
            "GHSA-hh2w-p6rv-4g7w,NuGet,System.Text.Json,7.0.0,8.0.4",
            "GHSA-hh2w-p6rv-4g7w,NuGet,System.Text.Json,8.0.3,8.0.4",
            "GHSA-hh2w-p6rv-4g7w,NuGet,system.text.json,8.0.3,8.0.4",
            "GHSA-hh2w-p6rv-4g7w,NuGet,SYSTEM.TEXT.JSON,8.0.3,8.0.4",
            "GHSA-hh2w-p6rv-4g7w,NuGet,System.Text.Json,08.0.03.0,8.0.4"})
    void pinnedOfficialAdvisorySelectsTheInstalledReleaseFixInBothModes(String id, String ecosystem, String name,
                                                                      String installed, String fixed) throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/advisories/" + id + ".json")) {
            assertThat(input).isNotNull();
            bytes = input.readAllBytes();
        }
        Map<String, Object> document = mapper.readValue(bytes, new TypeReference<>() { });
        var query = new OsvClient.OsvQuery(ecosystem, name, installed);
        var builder = org.springframework.web.client.RestClient.builder().baseUrl("https://api.osv.dev");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var client = new OsvClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api.osv.dev/v1/querybatch"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        mapper.writeValueAsString(Map.of("results", List.of(Map.of("vulns", List.of(
                                Map.of("id", document.get("id"), "modified", document.get("modified"))))))),
                        org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api.osv.dev/v1/vulns/" + document.get("id")))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        new String(bytes, java.nio.charset.StandardCharsets.UTF_8), org.springframework.http.MediaType.APPLICATION_JSON));
        var liveResult = client.queryBatch(List.of(query)).getFirst();
        assertThat(liveResult.resolved()).isTrue();
        assertThat(liveResult.vulns()).hasSize(1);
        OsvClient.OsvVuln live = liveResult.vulns().getFirst();
        server.verify();
        assertThat(live.fixVersion()).isEqualTo(fixed);

        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unresolved = new LinkedHashSet<>();
        ReflectionTestUtils.invokeMethod(new OsvBulkSource(mapper), "processVulnEntry", bytes, ecosystem.toUpperCase(java.util.Locale.ROOT),
                Map.of(name, Set.of(installed)), findings, unresolved);
        String key = AirgappedSnapshotService.componentKey(ecosystem, name, installed);
        assertThat(findings).containsKey(key);
        assertThat(unresolved).isEmpty();
        var snapshots = mock(AirgappedSnapshotService.class);
        when(snapshots.findOsvVulns(any())).thenReturn(findings);
        when(snapshots.findUnresolvedKeys(any())).thenReturn(unresolved);
        var offline = new OsvClient(snapshots, true).queryBatch(List.of(query)).getFirst();
        assertThat(offline.resolved()).isTrue();
        assertThat(offline.vulns()).singleElement().extracting(OsvClient.OsvVuln::fixVersion).isEqualTo(fixed);
    }
}
