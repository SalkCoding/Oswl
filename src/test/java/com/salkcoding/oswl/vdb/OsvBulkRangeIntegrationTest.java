package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class OsvBulkRangeIntegrationTest {
    private final OsvBulkSource source = new OsvBulkSource(new ObjectMapper());

    @Test
    void withdrawnEntryDoesNotProduceAnActiveFinding() {
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        process("""
                {"modified":"2024-09-01T00:00:00Z","id":"OSV-withdrawn","withdrawn":"2026-01-01T00:00:00Z","affected":[{
                "package":{"ecosystem":"npm","name":"example"},"versions":["1.0.0"]}]}
                """, Set.of("1.0.0"), findings, unknown);
        assertThat(findings).isEmpty();
        assertThat(unknown).isEmpty();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"true", "\"9999-01-01T00:00:00Z\""})
    void malformedWithdrawalCannotProduceAConfirmedCleanResult(String withdrawn) {
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        process("""
                {"modified":"2024-09-01T00:00:00Z","id":"OSV-invalid","withdrawn":%s,"affected":[{
                "package":{"ecosystem":"npm","name":"example"},"versions":["1.0.0"]}]}
                """.formatted(withdrawn), Set.of("1.0.0"), findings, unknown);
        assertThat(unknown).containsExactly(key("1.0.0"));
    }

    @Test
    void unionMatchesAreActuallyWrittenToTheSnapshotResults() {
        String advisory = """
                {"modified":"2024-09-01T00:00:00Z","id":"OSV-fixture","affected":[{"package":{"ecosystem":"npm","name":"example"},
                "versions":["9.0.0"],"ranges":[{"type":"SEMVER","events":[
                {"introduced":"0"},{"fixed":"1.0.0"},{"introduced":"2.0.0"},{"fixed":"3.0.0"}]}]}]}
                """;
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        process(advisory, Set.of("0.5.0", "1.5.0", "2.5.0", "9.0.0"), findings, unknown);
        assertThat(findings.keySet()).containsExactlyInAnyOrder(key("0.5.0"), key("2.5.0"), key("9.0.0"));
        assertThat(unknown).isEmpty();
    }

    @Test
    void gitAncestryWithoutEvidenceIsPreservedAsUnresolvedCoverage() {
        String advisory = """
                {"modified":"2024-09-01T00:00:00Z","id":"OSV-fixture","affected":[{"package":{"ecosystem":"npm","name":"example"},
                "ranges":[{"type":"GIT","repo":"https://example.invalid/repo","events":[{"introduced":"0"}]}]}]}
                """;
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        process(advisory, Set.of("1.0.0"), findings, unknown);
        assertThat(findings).isEmpty();
        assertThat(unknown).containsExactly(key("1.0.0"));
    }

    private void process(String advisory, Set<String> wanted, Map<String, List<SnapshotVuln>> findings, Set<String> unknown) {
        ReflectionTestUtils.invokeMethod(source, "processVulnEntry", advisory.getBytes(StandardCharsets.UTF_8),
                "NPM", Map.of("example", wanted), findings, unknown);
    }

    @Test
    void confirmedUnionDoesNotEraseUncertaintyFromAnotherAdvisory() {
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        process("""
                {"modified":"2024-09-01T00:00:00Z","id":"OSV-unresolved","affected":[{"package":{"ecosystem":"npm","name":"example"},
                "ranges":[{"type":"GIT","events":[{"introduced":"0"}]}]}]}
                """, Set.of("1.0.0"), findings, unknown);
        process("""
                {"modified":"2024-09-01T00:00:00Z","id":"OSV-confirmed","affected":[
                {"package":{"ecosystem":"npm","name":"example"},"versions":["1.0.0"]},
                {"package":{"ecosystem":"npm","name":"example"},"versions":["1.0.0"]}]}
                """, Set.of("1.0.0"), findings, unknown);
        assertThat(unknown).containsExactly(key("1.0.0"));
        assertThat(findings.get(key("1.0.0"))).singleElement()
                .extracting(SnapshotVuln::osvId).isEqualTo("OSV-confirmed");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"github-reviewed", "unreviewed", "alias-only", "other-host", "different-id", "missing-source", "mixed-source"})
    void sourceAttributedOriginalsRetainOfflineFixEvidence(String origin) throws Exception {
        String id = "GHSA-2345-6789-cfgh";
        String url = "https://github.com/github/advisory-database/blob/main/advisories/"
                + (origin.equals("unreviewed") ? "unreviewed" : "github-reviewed") + "/2024/09/" + id + "/" + id + ".json";
        if (origin.equals("other-host")) url = url.replace("github.com/", "github.com.example.invalid/");
        if (origin.equals("different-id")) url = url.replace(id, "GHSA-cfgh-jmpq-rvwx");
        String advisory = """
                {"id":"%s","modified":"2024-09-01T00:00:00Z","credits":[{"name":"Synthetic fixture author"}],
                "affected":[{"package":{"ecosystem":"npm","name":"example"},"database_specific":{"source":"%s"},
                "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"2.0.0"}]}]}]}
                """.formatted(id, url);
        var original = new ObjectMapper().readTree(advisory);
        if (origin.equals("alias-only")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) original).put("id", "OTHER-fixture").putArray("aliases").add(id);
        }
        if (origin.equals("missing-source")) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) original.path("affected").get(0)).remove("database_specific");
        }
        if (origin.equals("mixed-source")) {
            var additional = original.path("affected").get(0).deepCopy();
            ((com.fasterxml.jackson.databind.node.ObjectNode) additional).remove("database_specific");
            ((com.fasterxml.jackson.databind.node.ArrayNode) original.path("affected")).add(additional);
        }
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        process(original.toString(), Set.of("1.0.0"), findings, unknown);
        boolean retained = origin.equals("github-reviewed") || origin.equals("unreviewed");
        var stored = findings.get(key("1.0.0")).getFirst();
        if (retained) assertThat(stored.osvAdvisory()).isEqualTo(original);
        else assertThat(stored.osvAdvisory()).isNull();
        var snapshots = org.mockito.Mockito.mock(AirgappedSnapshotService.class);
        org.mockito.Mockito.when(snapshots.readOsvSnapshot(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(new AirgappedSnapshotService.VulnerabilitySnapshotView(findings, unknown, false, java.time.Instant.now().plusSeconds(600)));
        var result = new com.salkcoding.oswl.client.OsvClient(snapshots, true).queryBatch(List.of(
                new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "example", "1.0.0"))).getFirst();
        assertThat(result.commonFix().version()).isEqualTo(retained ? "2.0.0" : null);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"1.0.0,false,2.0.0", "3.5.0,false,4.0.0", "3.5.0,true,"})
    void retainedBulkEvidenceMatchesOnlineAdvisoryEvaluation(String version, boolean openEnded, String expectedFix) throws Exception {
        String id = "GHSA-2345-6789-cfgh";
        String original = """
                {"id":"%s","modified":"2024-09-01T00:00:00Z","summary":"Synthetic range fixture",
                "aliases":["CVE-2024-1000"],"database_specific":{"severity":"HIGH","cwe_ids":["CWE-79"]},
                "affected":[{"package":{"ecosystem":"npm","name":"example"},"database_specific":{"source":
                "https://github.com/github/advisory-database/blob/main/advisories/github-reviewed/2024/09/%s/%s.json"},
                "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"2.0.0"},{"introduced":"3.0.0"}%s]}]}]}
                """.formatted(id, id, id, openEnded ? "" : ",{" + "\"fixed\":\"4.0.0\"}");
        var query = new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "example", version);
        var builder = org.springframework.web.client.RestClient.builder().baseUrl("https://api.osv.dev");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var live = new com.salkcoding.oswl.client.OsvClient();
        ReflectionTestUtils.setField(live, "restClient", builder.build());
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api.osv.dev/v1/querybatch"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"results\":[{\"vulns\":[{\"id\":\"" + id + "\",\"modified\":\"2024-09-01T00:00:00Z\"}]}]}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api.osv.dev/v1/vulns/" + id))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(original,
                        org.springframework.http.MediaType.APPLICATION_JSON));
        var online = live.queryBatch(List.of(query)).getFirst();
        Map<String, List<SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        process(original, Set.of(version), findings, unknown);
        var snapshots = org.mockito.Mockito.mock(AirgappedSnapshotService.class);
        org.mockito.Mockito.when(snapshots.readOsvSnapshot(org.mockito.ArgumentMatchers.anyCollection()))
                .thenReturn(new AirgappedSnapshotService.VulnerabilitySnapshotView(findings, unknown, false, online.validUntil()));
        var offline = new com.salkcoding.oswl.client.OsvClient(snapshots, true).queryBatch(List.of(query)).getFirst();
        assertThat(online.resolved()).isTrue();
        assertThat(online.commonFix().version()).isEqualTo(expectedFix);
        assertThat(online.vulns()).hasSize(1);
        assertThat(online.advisoryRevisions()).containsKey(id);
        assertThat(online.advisoryDigests()).containsKey(id);
        assertThat(offline).isEqualTo(online);
        server.verify();
    }

    private String key(String version) {
        return AirgappedSnapshotService.componentKey("NPM", "example", version);
    }
}
