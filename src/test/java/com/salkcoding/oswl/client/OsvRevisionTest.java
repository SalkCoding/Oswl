package com.salkcoding.oswl.client;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OsvRevisionTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.osv.dev");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OsvClient client = new OsvClient();

    OsvRevisionTest() { ReflectionTestUtils.setField(client, "restClient", builder.build()); }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"[]", "[{}]",
            "[{\"package\":{\"ecosystem\":\"npm\",\"name\":\"other\"},\"versions\":[\"1.2.3\"]}]",
            "[{\"package\":{\"ecosystem\":\"PyPI\",\"name\":\"example\"},\"versions\":[\"1.2.3\"]}]",
            "[{\"package\":{\"ecosystem\":\"npm\",\"name\":\"example\"},\"versions\":false}]",
            "[{\"package\":{\"ecosystem\":\"npm\",\"name\":\"example\"},\"ranges\":[{\"type\":\"GIT\",\"events\":[{\"introduced\":\"0\"}]}]}]"})
    void mixedUncertainMembershipPreservesConfirmedFindingsWithoutInventingACommonFix(String affected) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var known = mapper.readTree("""
                {"id":"OSV-known","modified":"2026-01-01T00:00:00Z","affected":[{
                "package":{"ecosystem":"npm","name":"example"},"ranges":[{"type":"SEMVER",
                "events":[{"introduced":"0"},{"fixed":"1.2.4"}]}]}]}
                """);
        var uncertain = mapper.readTree("{\"id\":\"OSV-uncertain\",\"modified\":\"2026-01-01T00:00:00Z\",\"affected\":" + affected + "}");
        for (boolean reverse : new boolean[] {false, true}) {
            server.reset();
            var originals = reverse ? List.of(uncertain, known) : List.of(known, uncertain);
            var stubs = originals.stream().map(raw -> java.util.Map.of("id", raw.path("id").asText(),
                    "modified", raw.path("modified").asText())).toList();
            batch(mapper.writeValueAsString(java.util.Map.of("results", List.of(java.util.Map.of("vulns", stubs)))));
            for (var original : originals) server.expect(requestTo("https://api.osv.dev/v1/vulns/" + original.path("id").asText()))
                    .andRespond(withSuccess(original.toString(), MediaType.APPLICATION_JSON));
            var online = client.queryBatch(List.of(query())).getFirst();
            var snapshots = org.mockito.Mockito.mock(com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.class);
            String key = com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.componentKey("npm", "example", query().version());
            var records = originals.stream().map(raw -> new com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln(
                    raw.path("id").asText(), null, null, "99.0.0", null, null, null, null, null, java.util.Set.of(), raw)).toList();
            org.mockito.Mockito.when(snapshots.findOsvVulns(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Map.of(key, records));
            var offline = new OsvClient(snapshots, true).queryBatch(List.of(query())).getFirst();
            assertThat(offline).isEqualTo(online);
            assertThat(online.resolved()).isFalse();
            assertThat(online.commonFix().version()).isNull();
            assertThat(online.advisoryRevisions()).containsOnlyKeys("OSV-known");
            assertThat(online.vulns()).singleElement().satisfies(v -> {
                assertThat(v.osvId()).isEqualTo("OSV-known");
                assertThat(v.fixVersion()).isEqualTo("1.2.4");
            });
            server.verify();
        }
    }

    @Test
    void laterConflictingRevisionRemovesEarlierAcceptedEvidence() {
        batch("{\"results\":[{\"vulns\":[" + stub("2026-01-01T00:00:00Z") + "],\"next_page_token\":\"cursor\"}]}");
        server.expect(requestTo("https://api.osv.dev/v1/vulns/OSV-fixture"))
                .andRespond(withSuccess("""
                        {"id":"OSV-fixture","modified":"2026-01-01T00:00:00Z","affected":[{
                        "package":{"ecosystem":"npm","name":"example"},"ranges":[{"type":"SEMVER",
                        "events":[{"introduced":"0"},{"fixed":"1.2.4"}]}]}]}
                        """, MediaType.APPLICATION_JSON));
        batch("{\"results\":[{\"vulns\":[" + stub("2026-01-02T00:00:00Z") + "]}]}");
        var result = client.queryBatch(List.of(query())).getFirst();
        assertThat(result.resolved()).isFalse();
        assertThat(result.commonFix().version()).isNull();
        assertThat(result.advisoryRevisions()).isEmpty();
        assertThat(result.vulns()).singleElement().satisfies(v -> {
            assertThat(v.osvId()).isEqualTo("OSV-fixture");
            assertThat(v.fixVersion()).isNull();
        });
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"missing", "zero", "critical", "malformed-summary"})
    void originalAdvisoryDetailsAreIdenticalAcrossModesDespiteStaleProjection(String scoreState) throws Exception {
        var raw = (com.fasterxml.jackson.databind.node.ObjectNode) new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"id":"OSV-fixture","modified":"2026-01-01T00:00:00Z","aliases":["CVE-2026-0001"],
                "summary":"source summary","database_specific":{"cwe_ids":["CWE-79"]},
                "affected":[{"package":{"ecosystem":"npm","name":"example"},
                "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"1.2.4"}]}]}]}
                """);
        if (scoreState.equals("malformed-summary")) raw.putObject("summary").put("unexpected", "object");
        if (scoreState.equals("zero") || scoreState.equals("critical")) {
            raw.putArray("severity").addObject().put("type", "CVSS_V3").put("score", scoreState.equals("zero")
                    ? "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:N" : "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H");
        }
        batch("{\"results\":[{\"vulns\":[" + stub("2026-01-01T00:00:00Z") + "]}]}");
        server.expect(requestTo("https://api.osv.dev/v1/vulns/OSV-fixture")).andRespond(withSuccess(raw.toString(), MediaType.APPLICATION_JSON));
        var online = client.queryBatch(List.of(query())).getFirst();
        var snapshots = org.mockito.Mockito.mock(com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.class);
        String key = com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.componentKey("npm", "example", query().version());
        org.mockito.Mockito.when(snapshots.findOsvVulns(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Map.of(key, List.of(
                new com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln("OSV-fixture", "CVE-WRONG", "stale summary",
                        "99.0.0", "CWE-999", "HIGH", 8.1, "stale-vector", null, java.util.Set.of(), raw))));
        var offline = new OsvClient(snapshots, true).queryBatch(List.of(query())).getFirst();
        assertThat(offline).isEqualTo(online);
        assertThat(offline.vulns()).singleElement().satisfies(v -> {
            assertThat(v.cveId()).isEqualTo("CVE-2026-0001");
            assertThat(v.summary()).isEqualTo(scoreState.equals("malformed-summary") ? null : "source summary");
            assertThat(v.cweId()).isEqualTo("CWE-79");
            Double expectedScore = switch (scoreState) { case "zero" -> 0.0; case "critical" -> 9.8; default -> null; };
            assertThat(v.cvssScore()).isEqualTo(expectedScore);
        });
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"2026-01-01T00:00:00Z,false,true", "9999-01-01T00:00:00Z,false,false", "9999-01-01T00:00:00Z,true,false"})
    void offlineOriginalDatesFollowTheOnlineRevisionClockCheck(String modified, boolean withdrawn, boolean complete) throws Exception {
        var raw = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"id":"OSV-fixture","modified":"%s","affected":[{"package":{"ecosystem":"npm","name":"example"},
                "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"1.2.4"}]}]}]}
                """.formatted(modified));
        if (withdrawn) ((com.fasterxml.jackson.databind.node.ObjectNode) raw).put("withdrawn", "2026-01-01T00:00:00Z");
        batch("{\"results\":[{\"vulns\":[" + stub(modified) + "]}]}");
        server.expect(requestTo("https://api.osv.dev/v1/vulns/OSV-fixture")).andRespond(withSuccess(raw.toString(), MediaType.APPLICATION_JSON));
        var online = client.queryBatch(List.of(query())).getFirst();
        var snapshots = org.mockito.Mockito.mock(com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.class);
        String key = com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.componentKey("npm", "example", query().version());
        org.mockito.Mockito.when(snapshots.findOsvVulns(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Map.of(key, List.of(
                new com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln("OSV-fixture", null, null,
                        "1.2.4", null, null, null, null, null, java.util.Set.of(), raw))));
        var offline = new OsvClient(snapshots, true).queryBatch(List.of(query())).getFirst();
        assertThat(online.resolved()).isEqualTo(complete);
        assertThat(offline.resolved()).isEqualTo(online.resolved());
        assertThat(offline.commonFix()).isEqualTo(online.commonFix());
        assertThat(offline.vulns()).singleElement().satisfies(v -> {
            assertThat(v.osvId()).isEqualTo("OSV-fixture");
            assertThat(v.fixVersion()).isEqualTo(complete ? "1.2.4" : null);
        });
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"2024-01-01T00:00:00Z,true", "9999-01-01T00:00:00Z,false", "invalid,false"})
    void withdrawalMustHaveTakenEffectBeforeEitherModeHidesTheFinding(String withdrawn, boolean complete) throws Exception {
        var raw = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"id":"OSV-fixture","modified":"2026-01-01T00:00:00Z","withdrawn":"%s",
                "affected":[{"package":{"ecosystem":"npm","name":"example"},
                "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"1.2.4"}]}]}]}
                """.formatted(withdrawn));
        batch("{\"results\":[{\"vulns\":[" + stub("2026-01-01T00:00:00Z") + "]}]}");
        server.expect(requestTo("https://api.osv.dev/v1/vulns/OSV-fixture")).andRespond(withSuccess(raw.toString(), MediaType.APPLICATION_JSON));
        var online = client.queryBatch(List.of(query())).getFirst();
        var snapshots = org.mockito.Mockito.mock(com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.class);
        String key = com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.componentKey("npm", "example", query().version());
        org.mockito.Mockito.when(snapshots.findOsvVulns(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Map.of(key, List.of(
                new com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln("OSV-fixture", null, null,
                        "1.2.4", null, null, null, null, null, java.util.Set.of(), raw))));
        var offline = new OsvClient(snapshots, true).queryBatch(List.of(query())).getFirst();
        for (var result : List.of(online, offline)) {
            assertThat(result.resolved()).isEqualTo(complete);
            assertThat(result.commonFix().version()).isNull();
            if (complete) assertThat(result.vulns()).isEmpty();
            else assertThat(result.vulns()).singleElement().satisfies(v -> {
                assertThat(v.osvId()).isEqualTo("OSV-fixture");
                assertThat(v.fixVersion()).isNull();
            });
        }
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({"2026-01-01T00:00:00Z,2026-01-01T00:00:00Z,true",
            "2026-01-01T00:00:00Z,2026-01-01T00:00:00.000Z,true",
            "2026-01-01T00:00:00.123456Z,2026-01-01T00:00:00.123456789Z,true",
            "2026-01-01T00:00:00.123457Z,2026-01-01T00:00:00.123456789Z,false",
            "2026-01-01T00:00:00.123Z,2026-01-01T00:00:00.123456789Z,false",
            "2026-01-01T00:00:00.123456001Z,2026-01-01T00:00:00.123456789Z,false",
            "2026-01-01T00:00:00Z,2026-01-02T00:00:00Z,false",
            "2026-01-02T00:00:00Z,2026-01-01T00:00:00Z,false",
            "missing,missing,false", "missing,2026-01-01T00:00:00Z,false",
            "2026-01-01T00:00:00Z,missing,false", "invalid,invalid,false",
            "null,null,false", "true,true,false", "9999-01-01T00:00:00Z,9999-01-01T00:00:00Z,false"})
    void onlyMatchingValidRevisionsSupplyDetailedFindings(String queried, String hydrated, boolean complete) {
        batch("{\"results\":[{\"vulns\":[" + stub(queried) + "]}]}");
        detail(hydrated, "");
        var result = client.queryBatch(List.of(query())).getFirst();
        assertThat(result.resolved()).isEqualTo(complete);
        assertThat(result.vulns()).singleElement().satisfies(vuln -> {
            assertThat(vuln.osvId()).isEqualTo("OSV-fixture");
            assertThat(vuln.fixVersion()).isEqualTo(complete ? "1.2.4" : null);
            assertThat(vuln.summary()).isEqualTo(complete ? "fixture" : null);
        });
        server.verify();
    }

    @Test void aLaterRevisionConflictCannotKeepOrRestoreAnEarlierFix() {
        batch("{\"results\":[{\"vulns\":[" + stub("2026-01-01T00:00:00Z") + "],\"next_page_token\":\"cursor\"}]}");
        detail("2026-01-01T00:00:00Z", "");
        batch("{\"results\":[{\"vulns\":[" + stub("2026-01-02T00:00:00Z") + "," + stub("2026-01-01T00:00:00Z") + "]}]}");
        var result = client.queryBatch(List.of(query())).getFirst();
        assertThat(result.resolved()).isFalse();
        assertThat(result.vulns()).singleElement().satisfies(vuln -> assertThat(vuln.fixVersion()).isNull());
        server.verify();
    }

    @Test void withdrawalFromADifferentRevisionCannotEraseAQueriedFinding() {
        batch("{\"results\":[{\"vulns\":[" + stub("2026-01-01T00:00:00Z") + "]}]}");
        detail("2026-01-02T00:00:00Z", ",\"withdrawn\":\"2026-01-02T00:00:00Z\"");
        var result = client.queryBatch(List.of(query())).getFirst();
        assertThat(result.resolved()).isFalse();
        assertThat(result.vulns()).hasSize(1);
        server.verify();
    }

    @Test void sharedDetailCacheIsCheckedAgainstEachQueryRevision() {
        batch("{\"results\":[{\"vulns\":[" + stub("2026-01-01T00:00:00Z") + "]},{\"vulns\":["
                + stub("2026-01-02T00:00:00Z") + "]}]}");
        detail("2026-01-01T00:00:00Z", "");
        var results = client.queryBatch(List.of(query(), new OsvClient.OsvQuery("npm", "example", "1.2.2")));
        assertThat(results.get(0).resolved()).isTrue();
        assertThat(results.get(0).vulns().getFirst().fixVersion()).isEqualTo("1.2.4");
        assertThat(results.get(1).resolved()).isFalse();
        assertThat(results.get(1).vulns().getFirst().fixVersion()).isNull();
        server.verify();
    }

    private String stub(String revision) { return "{\"id\":\"OSV-fixture\"" + modified(revision) + "}"; }

    private String modified(String revision) {
        if (revision.equals("missing")) return "";
        return ",\"modified\":" + (revision.equals("null") || revision.equals("true") ? revision : "\"" + revision + "\"");
    }

    private void batch(String body) {
        server.expect(requestTo("https://api.osv.dev/v1/querybatch")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void detail(String revision, String extra) {
        server.expect(requestTo("https://api.osv.dev/v1/vulns/OSV-fixture")).andRespond(withSuccess("""
                {"id":"OSV-fixture","summary":"fixture"%s%s,
                "affected":[{"package":{"ecosystem":"npm","name":"example"},
                "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"1.2.4"}]}]}]}
                """.formatted(modified(revision), extra), MediaType.APPLICATION_JSON));
    }

    private OsvClient.OsvQuery query() { return new OsvClient.OsvQuery("npm", "example", "1.2.3"); }
}
