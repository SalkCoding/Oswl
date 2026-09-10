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
    @CsvSource({"3.8.2,3.8.3", "4.3.6,4.3.8", "4.3.7,4.3.8", "5.1.4,5.1.5"})
    void pinnedOfficialAdvisorySelectsTheInstalledReleaseFixInBothModes(String installed, String fixed) throws Exception {
        byte[] bytes;
        try (var input = getClass().getResourceAsStream("/advisories/GHSA-wf6x-7x77-mvgw.json")) {
            assertThat(input).isNotNull();
            bytes = input.readAllBytes();
        }
        Map<String, Object> document = mapper.readValue(bytes, new TypeReference<>() { });
        var query = new OsvClient.OsvQuery("npm", "immutable", installed);
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
        ReflectionTestUtils.invokeMethod(new OsvBulkSource(mapper), "processVulnEntry", bytes, "NPM",
                Map.of("immutable", Set.of(installed)), findings, unresolved);
        String key = AirgappedSnapshotService.componentKey("npm", "immutable", installed);
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
