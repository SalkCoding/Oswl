package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.client.OsvClient;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OsvWildcardPackageTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"npm,1.0.0,true", "npm,2.0.0,false", "PyPI,1.0.0,true", "PyPI,2.0.0,false",
            "NuGet,1.0.0,true", "NuGet,2.0.0,false"})
    void wildcardUsesTheSameRangeAndFixEvidenceOnlineAndOffline(String ecosystem, String version, boolean affected) throws Exception {
        String original = advisory(ecosystem, "*", "0", "2.0.0");
        var builder = RestClient.builder().baseUrl("https://api.osv.dev");
        var server = MockRestServiceServer.bindTo(builder).build();
        var online = new OsvClient();
        ReflectionTestUtils.setField(online, "restClient", builder.build());
        server.expect(requestTo("https://api.osv.dev/v1/querybatch")).andRespond(withSuccess(
                "{\"results\":[{\"vulns\":[{\"id\":\"OSV-wildcard\",\"modified\":\"2026-01-01T00:00:00Z\"}]}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.osv.dev/v1/vulns/OSV-wildcard")).andRespond(withSuccess(original, MediaType.APPLICATION_JSON));
        var query = new OsvClient.OsvQuery(ecosystem, "example", version);
        var live = online.queryBatch(List.of(query)).getFirst();
        server.verify();
        assertThat(live.resolved()).isTrue();
        assertThat(live.vulns()).hasSize(affected ? 1 : 0);
        assertThat(live.commonFix().version()).isEqualTo(affected ? "2.0.0" : null);

        var store = mock(AirgappedSnapshotService.class);
        String key = AirgappedSnapshotService.componentKey(ecosystem, "example", version);
        var raw = new SnapshotVuln("OSV-wildcard", null, null, null, null, null, null, null, null, Set.of(), mapper.readTree(original));
        when(store.findOsvVulns(anyCollection())).thenReturn(Map.of(key, List.of(raw)));
        var offline = new OsvClient(store, true).queryBatch(List.of(query)).getFirst();
        assertThat(offline).isEqualTo(live);
    }

    @ParameterizedTest
    @ValueSource(strings = {"npm", "PyPI", "NuGet"})
    void bulkExpandsWildcardOnlyToWantedNamesAndKeepsConcreteFixConstraints(String ecosystem) throws Exception {
        var document = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(advisory(ecosystem, "*", "0", "2.0.0"));
        ((com.fasterxml.jackson.databind.node.ArrayNode) document.path("affected"))
                .add(mapper.readTree(advisory(ecosystem, "example", "2.0.0", "3.0.0")).path("affected").get(0));
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        ReflectionTestUtils.invokeMethod(new OsvBulkSource(mapper), "processVulnEntry",
                document.toString().getBytes(StandardCharsets.UTF_8), ecosystem.toUpperCase(Locale.ROOT),
                Map.of("example", Set.of("1.0.0", "2.0.0", "3.0.0"), "another", Set.of("1.0.0")), findings, unknown);
        assertThat(unknown).isEmpty();
        assertThat(findings).hasSize(3);
        assertThat(findings.get(AirgappedSnapshotService.componentKey(ecosystem, "example", "1.0.0")))
                .singleElement().extracting(SnapshotVuln::fixVersion).isEqualTo("3.0.0");
        assertThat(findings.get(AirgappedSnapshotService.componentKey(ecosystem, "example", "2.0.0")))
                .singleElement().extracting(SnapshotVuln::fixVersion).isEqualTo("3.0.0");
        assertThat(findings.get(AirgappedSnapshotService.componentKey(ecosystem, "another", "1.0.0")))
                .singleElement().extracting(SnapshotVuln::fixVersion).isEqualTo("2.0.0");
    }

    @ParameterizedTest
    @CsvSource({"npm,PyPI,*", "Debian:11,Debian:12,*", "npm,npm,exam*", "npm,npm,other"})
    void wildcardDoesNotBroadenEcosystemsReleasesOrPartialNames(String queried, String declared, String name) throws Exception {
        var raw = mapper.readTree(advisory(declared, name, "0", "2.0.0"));
        assertThat(OsvRangeEvaluator.evaluateAdvisory(raw, queried, "example", "1.0.0"))
                .isEqualTo(OsvRangeEvaluator.Result.UNKNOWN);
        assertThat(OsvFixVersionSelector.select(raw, queried, "example", "1.0.0").version()).isNull();
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        ReflectionTestUtils.invokeMethod(new OsvBulkSource(mapper), "processVulnEntry", raw.toString().getBytes(StandardCharsets.UTF_8),
                queried.toUpperCase(Locale.ROOT), Map.of("example", Set.of("1.0.0")), findings, unknown);
        assertThat(findings).isEmpty();
        assertThat(unknown).isEmpty();
    }

    private String advisory(String ecosystem, String name, String introduced, String fixed) {
        return """
                {"id":"OSV-wildcard","modified":"2026-01-01T00:00:00Z","affected":[{
                "package":{"ecosystem":"%s","name":"%s"},
                "ranges":[{"type":"ECOSYSTEM","events":[{"introduced":"%s"},{"fixed":"%s"}]}]}]}
                """.formatted(ecosystem, name, introduced, fixed);
    }

    @ParameterizedTest
    @ValueSource(strings = {"fixed", "unfixed", "unsupported"})
    void wildcardUpgradeConstraintsCannotBeDroppedFromTheCommonFix(String state) throws Exception {
        var current = mapper.readTree(advisory("npm", "example", "0", "2.0.0"));
        var later = mapper.readTree(advisory("npm", "*", "2.0.0", "3.0.0"));
        var range = (com.fasterxml.jackson.databind.node.ObjectNode) later.path("affected").get(0).path("ranges").get(0);
        if (state.equals("unfixed")) ((com.fasterxml.jackson.databind.node.ArrayNode) range.path("events")).remove(1);
        if (state.equals("unsupported")) range.put("type", "GIT");
        for (boolean reverse : new boolean[] {false, true}) {
            var originals = reverse ? List.of(later, current) : List.of(current, later);
            assertThat(OsvFixVersionSelector.selectAcrossAdvisories(originals, "npm", "example", "1.0.0").version())
                    .isEqualTo(state.equals("fixed") ? "3.0.0" : null);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"missing", "unsupported"})
    void wildcardWithoutUsableVersionEvidenceLeavesEveryWantedComponentUnresolved(String state) throws Exception {
        var original = mapper.readTree(advisory("npm", "*", "0", "2.0.0"));
        var affected = (com.fasterxml.jackson.databind.node.ObjectNode) original.path("affected").get(0);
        if (state.equals("missing")) affected.remove("ranges");
        else ((com.fasterxml.jackson.databind.node.ObjectNode) affected.path("ranges").get(0)).put("type", "GIT");
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        ReflectionTestUtils.invokeMethod(new OsvBulkSource(mapper), "processVulnEntry", original.toString().getBytes(StandardCharsets.UTF_8),
                "NPM", Map.of("example", Set.of("1.0.0"), "another", Set.of("2.0.0")), findings, unknown);
        assertThat(findings).isEmpty();
        assertThat(unknown).containsExactlyInAnyOrder(AirgappedSnapshotService.componentKey("npm", "example", "1.0.0"),
                AirgappedSnapshotService.componentKey("npm", "another", "2.0.0"));
        assertThat(OsvRangeEvaluator.evaluateAdvisory(original, "npm", "example", "1.0.0"))
                .isEqualTo(OsvRangeEvaluator.Result.UNKNOWN);
        assertThat(OsvFixVersionSelector.select(original, "npm", "example", "1.0.0").version()).isNull();
    }
}
