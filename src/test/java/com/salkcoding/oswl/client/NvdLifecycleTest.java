package com.salkcoding.oswl.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.domain.enums.MatchConfidence;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.dto.snapshot.SnapshotLookup;
import com.salkcoding.oswl.service.vulnerability.sources.NvdAdvisorySource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.*;
import java.util.stream.Stream;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NvdLifecycleTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"Analyzed", "Rejected"})
    void selectingNewestDoesNotRepairIncompleteSnapshotCoverage(String status) {
        var older = cve("CVE-2026-700006", "2024-01-01T00:00:00Z", "Analyzed", RiskLevel.HIGH, "older");
        var newer = cve("CVE-2026-700006", "2024-01-02T00:00:00", status, RiskLevel.LOW, "newer");
        var source = new NvdAdvisorySource(mock(NvdClient.class), mock(CpeMatchService.class));
        var result = source.lookupSnapshot("fixture", "1", null, new SnapshotLookup<>(List.of(older, newer), false));
        assertThat(result.lookupFailed()).isTrue();
        assertThat(status.equals("Rejected") ? result.withdrawnFindings() : result.findings()).containsExactly(newer);
        assertThat(status.equals("Rejected") ? result.findings() : result.withdrawnFindings()).isEmpty();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void validRevisionsChooseTheNewestObservationRegardlessOfSourceOrOrder(boolean offline, boolean reverse) {
        var older = cve("CVE-2026-700001", "2024-01-01T00:00:00Z", "Analyzed", RiskLevel.HIGH, "older");
        var newer = cve("CVE-2026-700001", "2024-01-02T00:00:00Z", "Analyzed", RiskLevel.LOW, "newer");
        var records = reverse ? List.of(newer, older) : List.of(older, newer);
        var result = runLifecycle(offline, records);

        assertThat(result.lookupFailed()).isFalse();
        assertThat(result.withdrawnFindings()).isEmpty();
        assertThat(result.findings()).containsExactly(newer);
        assertThat(result.findings().getFirst().severity()).isEqualTo(RiskLevel.LOW);
        assertThat(records).containsExactly(reverse ? newer : older, reverse ? older : newer);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void newestRejectedRevisionIsWithdrawnOnlyRegardlessOfSourceOrOrder(boolean offline, boolean reverse) {
        var older = cve("CVE-2026-700002", "2024-01-01T00:00:00Z", "Analyzed", RiskLevel.HIGH, "older");
        var newer = cve("CVE-2026-700002", "2024-01-02T00:00:00Z", "Rejected", RiskLevel.HIGH, "newer");
        var records = reverse ? List.of(newer, older) : List.of(older, newer);
        var result = runLifecycle(offline, records);

        assertThat(result.lookupFailed()).isFalse();
        assertThat(result.findings()).isEmpty();
        assertThat(result.withdrawnFindings()).containsExactly(newer);
        assertThat(records).containsExactly(reverse ? newer : older, reverse ? older : newer);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void newestAnalyzedRevisionReplacesOlderRejectedRevision(boolean offline, boolean reverse) {
        var older = cve("CVE-2026-700003", "2024-01-01T00:00:00Z", "Rejected", RiskLevel.HIGH, "older");
        var newer = cve("CVE-2026-700003", "2024-01-02T00:00:00Z", "Analyzed", RiskLevel.LOW, "newer");
        var records = reverse ? List.of(newer, older) : List.of(older, newer);
        var result = runLifecycle(offline, records);

        assertThat(result.lookupFailed()).isFalse();
        assertThat(result.withdrawnFindings()).isEmpty();
        assertThat(result.findings()).containsExactly(newer);
        assertThat(result.findings().getFirst().severity()).isEqualTo(RiskLevel.LOW);
        assertThat(records).containsExactly(reverse ? newer : older, reverse ? older : newer);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false", "true"})
    void sameNewestRevisionWithDifferentContentRemainsAConflict(boolean offline) {
        var first = cve("CVE-2026-700004", "2024-01-02T00:00:00Z", "Analyzed", RiskLevel.HIGH, "first");
        var second = cve("CVE-2026-700004", "2024-01-02T00:00:00Z", "Analyzed", RiskLevel.LOW, "second");
        var records = List.of(first, second);
        var result = runLifecycle(offline, records);

        assertThat(result.lookupFailed()).isTrue();
        assertThat(result.withdrawnFindings()).isEmpty();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.cveId()).isEqualTo("CVE-2026-700004");
            assertThat(finding.nvdApplicability()).contains("conflictingRecords", "first", "second");
        });
        assertThat(records).containsExactly(first, second);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,missing", "false,invalid", "false,future",
            "true,missing", "true,invalid", "true,future"})
    void unusableRevisionAnywherePreservesConflictInsteadOfChoosingWinner(boolean offline, String revision) {
        var valid = cve("CVE-2026-700005", "2024-01-02T00:00:00Z", "Analyzed", RiskLevel.LOW, "valid");
        String raw = switch (revision) {
            case "missing" -> "{\"id\":\"CVE-2026-700005\",\"vulnStatus\":\"Analyzed\",\"description\":\"unusable\"}";
            case "invalid" -> "{\"id\":\"CVE-2026-700005\",\"vulnStatus\":\"Analyzed\",\"lastModified\":\"not-a-time\",\"description\":\"unusable\"}";
            default -> "{\"id\":\"CVE-2026-700005\",\"vulnStatus\":\"Analyzed\",\"lastModified\":\"2999-01-01T00:00:00Z\",\"description\":\"unusable\"}";
        };
        var unusable = new NvdClient.NvdCve("CVE-2026-700005", "unusable", RiskLevel.HIGH, null, null,
                MatchConfidence.HIGH, raw);
        var records = List.of(valid, unusable);
        var result = runLifecycle(offline, records);

        assertThat(result.lookupFailed()).isTrue();
        assertThat(result.withdrawnFindings()).isEmpty();
        assertThat(result.findings()).singleElement().satisfies(finding ->
                assertThat(finding.nvdApplicability()).contains("conflictingRecords", "valid", "unusable"));
        assertThat(records).containsExactly(valid, unusable);
    }

    private static NvdClient.NvdCve cve(String id, String revision, String status, RiskLevel severity, String description) {
        String raw = "{\"id\":\"%s\",\"vulnStatus\":\"%s\",\"lastModified\":\"%s\",\"description\":\"%s\"}"
                .formatted(id, status, revision, description);
        return new NvdClient.NvdCve(id, description, severity, null, null, MatchConfidence.HIGH, raw);
    }

    private static com.salkcoding.oswl.service.vulnerability.sources.AdvisoryFetchResult<NvdClient.NvdCve>
    runLifecycle(boolean offline, List<NvdClient.NvdCve> records) {
        var client = mock(NvdClient.class);
        var cpe = mock(CpeMatchService.class);
        var source = new NvdAdvisorySource(client, cpe);
        if (offline) return source.lookupSnapshot("fixture", "1", null, new SnapshotLookup<>(records, true));
        when(cpe.inferCpes("fixture", "1")).thenReturn(records.stream()
                .map(r -> new CpeNameMapper.CpeCandidate("fixture", r.description(), "1", MatchConfidence.HIGH)).toList());
        var responses = records.iterator();
        when(client.findByCpeName(anyString(), any())).thenAnswer(invocation -> List.of(responses.next()));
        return source.lookup("fixture", "1", null, List.of());
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> ambiguousEvidence() {
        return Stream.of("duplicate-status", "duplicate-id", "trailing-record")
                .flatMap(shape -> Stream.of("alone", "valid-first", "invalid-first")
                        .map(order -> org.junit.jupiter.params.provider.Arguments.of(shape, order)));
    }

    @ParameterizedTest @MethodSource("ambiguousEvidence")
    void ambiguousJsonCannotEstablishRejectionOrDisappearDuringDeduplication(String shape, String order) {
        String valid = "{\"id\":\"CVE-2026-123450\",\"vulnStatus\":\"Rejected\",\"lastModified\":\"2020-01-01T00:00:00Z\"}";
        String raw = switch (shape) {
            case "duplicate-status" -> valid.replace("\"vulnStatus\":", "\"vulnStatus\":\"Analyzed\",\"vulnStatus\":");
            case "duplicate-id" -> valid.replace("\"id\":", "\"id\":\"CVE-2026-999999\",\"id\":");
            default -> valid + " {\"vulnStatus\":\"Analyzed\"}";
        };
        var good = new NvdClient.NvdCve("CVE-2026-123450",null,null,null,null,MatchConfidence.HIGH,valid);
        var uncertain = new NvdClient.NvdCve("CVE-2026-123450",null,null,null,null,MatchConfidence.HIGH,raw);
        var input = switch (order) {
            case "valid-first" -> List.of(good, uncertain);
            case "invalid-first" -> List.of(uncertain, good);
            default -> List.of(uncertain);
        };
        var source = new NvdAdvisorySource(mock(NvdClient.class),mock(CpeMatchService.class));
        var result = source.lookupSnapshot("fixture","1",null,new SnapshotLookup<>(input,true));
        assertThat(result.lookupFailed()).isTrue();
        assertThat(result.withdrawnFindings()).isEmpty();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.cveId()).isEqualTo(uncertain.cveId());
            if (order.equals("alone")) assertThat(finding.nvdApplicability()).isEqualTo(raw);
            else {
                try {
                    var alternatives = new ObjectMapper().readTree(finding.nvdApplicability()).path("conflictingRecords");
                    assertThat(alternatives).hasSize(2);
                    assertThat(alternatives.findValuesAsText("nvdApplicability")).containsExactlyInAnyOrder(valid, raw);
                } catch (java.io.IOException e) { throw new AssertionError(e); }
            }
        });
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"wrong-id","broken-json","wrong-status-type","stale"})
    void uncertainEvidenceCannotRemoveAStoredIdentifier(String state) {
        String raw = switch(state) {
            case "wrong-id" -> "{\"id\":\"CVE-2026-999999\",\"vulnStatus\":\"Rejected\",\"lastModified\":\"2020-01-01T00:00:00Z\"}";
            case "broken-json" -> "broken";
            case "wrong-status-type" -> "{\"id\":\"CVE-2026-123450\",\"vulnStatus\":true}";
            default -> "{\"id\":\"CVE-2026-123450\",\"vulnStatus\":\"Rejected\",\"lastModified\":\"2020-01-01T00:00:00Z\"}";
        };
        var source = new NvdAdvisorySource(mock(NvdClient.class),mock(CpeMatchService.class));
        var other = new NvdClient.NvdCve("CVE-2026-123451",null,null,null,null,MatchConfidence.LOW);
        var rejected = new NvdClient.NvdCve("CVE-2026-123450",null,null,null,null,MatchConfidence.HIGH,raw);
        var snapshot = new SnapshotLookup<>(List.of(rejected,other),!state.equals("stale"));
        var result = source.lookupSnapshot("fixture","1",null,snapshot);

        assertThat(result.withdrawnFindings()).containsExactlyElementsOf(state.equals("stale") ? List.of(rejected) : List.of());
        assertThat(result.lookupFailed()).isTrue();
        assertThat(result.findings()).contains(other);
        if (!state.equals("stale")) assertThat(result.findings()).contains(rejected);
        assertThat(snapshot.findings()).containsExactly(rejected,other);
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void conflictingRejectionCannotDependOnCandidateOrder(boolean offline, boolean reverse) throws Exception {
        var active = new NvdClient.NvdCve("CVE-2026-123450", "active", null, null, null, MatchConfidence.HIGH,
                "{\"id\":\"CVE-2026-123450\",\"vulnStatus\":\"Analyzed\",\"lastModified\":\"2020-01-01T00:00:00Z\"}");
        var rejected = new NvdClient.NvdCve("CVE-2026-123450", "rejected", null, null, null, MatchConfidence.LOW,
                "{\"id\":\"CVE-2026-123450\",\"vulnStatus\":\"Rejected\",\"lastModified\":\"2020-01-01T00:00:00Z\"}");
        var records = reverse ? List.of(rejected,active) : List.of(active,rejected);
        var client=mock(NvdClient.class); var cpe=mock(CpeMatchService.class);
        var source=new NvdAdvisorySource(client,cpe);
        com.salkcoding.oswl.service.vulnerability.sources.AdvisoryFetchResult<NvdClient.NvdCve> result;
        if (offline) result=source.lookupSnapshot("fixture","1",null,new SnapshotLookup<>(records,true));
        else {
            when(cpe.inferCpes("fixture","1")).thenReturn(List.of(
                    new CpeNameMapper.CpeCandidate("fixture","one","1",MatchConfidence.HIGH),
                    new CpeNameMapper.CpeCandidate("fixture","two","1",MatchConfidence.LOW)));
            when(client.findByCpeName(anyString(),any())).thenReturn(List.of(records.get(0)),List.of(records.get(1)));
            result=source.lookup("fixture","1",null,List.of());
        }
        assertThat(result.lookupFailed()).isTrue();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.cveId()).isEqualTo("CVE-2026-123450");
            assertThat(finding.severity()).isNull();
            assertThat(finding.nvdApplicability()).contains("Analyzed", "Rejected");
        });
    }

    @org.junit.jupiter.api.Test
    void equivalentEvidenceDeduplicatesButConflictSurvivesSerialization() throws Exception {
        String raw = "{\"id\":\"CVE-2026-123450\",\"vulnStatus\":\"Analyzed\"}";
        String reordered = "{\"vulnStatus\":\"Analyzed\",\"id\":\"CVE-2026-123450\"}";
        var first = new NvdClient.NvdCve("CVE-2026-123450",null,null,null,null,MatchConfidence.HIGH,raw);
        var equivalent = new NvdClient.NvdCve("CVE-2026-123450",null,null,null,null,MatchConfidence.LOW,reordered);
        var source = new NvdAdvisorySource(mock(NvdClient.class),mock(CpeMatchService.class));
        var same=source.lookupSnapshot("fixture","1",null,new SnapshotLookup<>(List.of(first,equivalent),true));
        assertThat(same.lookupFailed()).isFalse();
        assertThat(same.findings()).containsExactly(first);
        var changed = new NvdClient.NvdCve("CVE-2026-123450","changed",null,null,null,MatchConfidence.HIGH,raw);
        var conflict=source.lookupSnapshot("fixture","1",null,new SnapshotLookup<>(List.of(first,changed),true));
        var json=new ObjectMapper();
        var copy=json.readValue(json.writeValueAsString(conflict.findings().getFirst()),NvdClient.NvdCve.class);
        var reread=source.lookupSnapshot("fixture","1",null,new SnapshotLookup<>(List.of(copy),true));
        assertThat(reread.lookupFailed()).isTrue();
        assertThat(json.readTree(copy.nvdApplicability()).path("conflictingRecords")).hasSize(2);
        assertThat(reread.findings()).containsExactly(copy);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> states() {
        return Stream.of(false,true).flatMap(offline -> Stream.of("rejected","active","future","missing-time","invalid-time",
                        "active-future", "active-invalid-time", "active-null-time", "active-number-time",
                        "active-missing-time", "missing-status-future", "missing-status-valid")
                .map(state -> org.junit.jupiter.params.provider.Arguments.of(offline,state)));
    }
    @ParameterizedTest @MethodSource("states")
    void rejectionNeedsUsableRevisionEvidenceAcrossModes(boolean offline, String state) throws Exception {
        var json = new ObjectMapper();
        var record = json.createObjectNode().put("id","CVE-2026-123450")
                .put("vulnStatus",state.startsWith("active") ? "Analyzed" : "Rejected");
        if (state.startsWith("missing-status")) record.remove("vulnStatus");
        if (!state.endsWith("missing-time")) record.put("lastModified", switch(state) {
            case "future", "active-future", "missing-status-future" -> "2999-01-01T00:00:00.000";
            case "invalid-time", "active-invalid-time" -> "not-a-time";
            default -> "2020-01-01T00:00:00.000";
        });
        if (state.equals("active-null-time")) record.putNull("lastModified");
        if (state.equals("active-number-time")) record.put("lastModified", 1);
        var cpe = mock(CpeMatchService.class);
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new NvdClient();
        ReflectionTestUtils.setField(client,"restClient",builder.build());
        ReflectionTestUtils.setField(client,"minIntervalMs",0L);
        var source = new NvdAdvisorySource(client,cpe);
        com.salkcoding.oswl.service.vulnerability.sources.AdvisoryFetchResult<NvdClient.NvdCve> result;
        if (offline) {
            result = source.lookupSnapshot("fixture","1",null,new SnapshotLookup<>(List.of(
                    new NvdClient.NvdCve("CVE-2026-123450",null,null,null,null,MatchConfidence.HIGH,record.toString())),true));
        } else {
            when(cpe.inferCpes("fixture","1")).thenReturn(List.of(new CpeNameMapper.CpeCandidate("fixture","product","1",MatchConfidence.HIGH)));
            server.expect(anything()).andRespond(withSuccess(json.writeValueAsString(Map.of("startIndex",0,"resultsPerPage",1,
                    "totalResults",1,"vulnerabilities",List.of(Map.of("cve",record)))),MediaType.APPLICATION_JSON));
            result = source.lookup("fixture","1",null,List.of());
            server.verify();
        }
        assertThat(result.lookupFailed()).isEqualTo(!Set.of("active","rejected","active-missing-time","missing-status-valid").contains(state));
        assertThat(result.findings()).hasSize(state.equals("rejected") ? 0 : 1);
        assertThat(result.withdrawnFindings()).hasSize(state.equals("rejected") ? 1 : 0);
        if (state.equals("rejected")) {
            assertThat(json.readTree(result.withdrawnFindings().getFirst().nvdApplicability())).isEqualTo(record);
        }
        if (!state.equals("rejected")) {
            assertThat(result.findings().getFirst().cveId()).isEqualTo("CVE-2026-123450");
            assertThat(json.readTree(result.findings().getFirst().nvdApplicability())).isEqualTo(record);
        }
    }
}
