package com.salkcoding.oswl.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.domain.enums.MatchConfidence;
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
        return Stream.of(false,true).flatMap(offline -> Stream.of("rejected","active","future","missing-time","invalid-time")
                .map(state -> org.junit.jupiter.params.provider.Arguments.of(offline,state)));
    }
    @ParameterizedTest @MethodSource("states")
    void rejectionNeedsUsableRevisionEvidenceAcrossModes(boolean offline, String state) throws Exception {
        var json = new ObjectMapper();
        var record = json.createObjectNode().put("id","CVE-2026-123450")
                .put("vulnStatus",state.equals("active") ? "Analyzed" : "Rejected");
        if (!state.equals("missing-time")) record.put("lastModified", switch(state) {
            case "future" -> "2999-01-01T00:00:00.000";
            case "invalid-time" -> "not-a-time";
            default -> "2020-01-01T00:00:00.000";
        });
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
        assertThat(result.lookupFailed()).isEqualTo(!Set.of("active","rejected").contains(state));
        assertThat(result.findings()).hasSize(state.equals("rejected") ? 0 : 1);
        if (!state.equals("rejected")) assertThat(result.findings().getFirst().cveId()).isEqualTo("CVE-2026-123450");
    }
}
