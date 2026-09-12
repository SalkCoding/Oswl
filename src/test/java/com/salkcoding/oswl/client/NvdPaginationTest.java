package com.salkcoding.oswl.client;

import com.salkcoding.oswl.domain.enums.MatchConfidence;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class NvdPaginationTest {
    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"same,false", "same,true", "next,false", "next,true", "malformed,false", "malformed,true"})
    void duplicateIdsKeepBothRevisionsForSourceReview(String placement, boolean reverse) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var active = java.util.Map.of("cve",java.util.Map.of("id","CVE-2026-123450","vulnStatus","Analyzed","lastModified","2020-01-01T00:00:00Z"));
        var rejected = java.util.Map.of("cve",java.util.Map.of("id","CVE-2026-123450","vulnStatus","Rejected","lastModified","2020-01-01T00:00:00Z"));
        var first = reverse ? rejected : active;
        var second = reverse ? active : rejected;
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = client(builder);
        if (placement.equals("same")) {
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything()).andRespond(withSuccess(
                    json.writeValueAsString(java.util.Map.of("startIndex",0,"totalResults",2,"resultsPerPage",2,
                            "vulnerabilities",java.util.List.of(first,second))),MediaType.APPLICATION_JSON));
        } else {
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.anything()).andRespond(withSuccess(
                    json.writeValueAsString(java.util.Map.of("startIndex",0,"totalResults",placement.equals("malformed") ? 3 : 2,
                            "resultsPerPage",1,"vulnerabilities",java.util.List.of(first))),MediaType.APPLICATION_JSON));
            server.expect(request -> assertThat(request.getURI().getRawQuery()).endsWith("startIndex=1")).andRespond(withSuccess(
                    json.writeValueAsString(java.util.Map.of("startIndex",1,"totalResults",placement.equals("malformed") ? 3 : 2,
                            "resultsPerPage",2,"vulnerabilities",placement.equals("malformed") ? java.util.Arrays.asList(second,null) : java.util.List.of(second))),MediaType.APPLICATION_JSON));
        }
        var cpe = org.mockito.Mockito.mock(CpeMatchService.class);
        org.mockito.Mockito.when(cpe.inferCpes("fixture","1")).thenReturn(java.util.List.of(
                new CpeNameMapper.CpeCandidate("fixture","product","1",MatchConfidence.HIGH)));
        var result = new com.salkcoding.oswl.service.vulnerability.sources.NvdAdvisorySource(client,cpe)
                .lookup("fixture","1",null,java.util.List.of());
        assertThat(result.lookupFailed()).isTrue();
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.cveId()).isEqualTo("CVE-2026-123450");
            assertThat(finding.nvdApplicability()).contains("conflictingRecords", "Analyzed", "Rejected");
        });
        server.verify();
    }

    private static final String URL = "https://services.nvd.nist.gov/rest/json/cves/2.0?cpeName=cpe%3Afixture";

    @ParameterizedTest
    @ValueSource(strings = {"complete", "transport", "json", "body", "offset", "total", "duplicate", "empty"})
    void laterPagesMustAgreeBeforeCoverageCanBeConfirmed(String kind) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = client(builder);
        server.expect(requestTo(URL)).andRespond(withSuccess(page(0, 2, "0001"), MediaType.APPLICATION_JSON));
        var next = server.expect(requestTo(URL + "&startIndex=1"));
        if (kind.equals("transport")) next.andRespond(withServerError());
        else if (kind.equals("json") || kind.equals("body")) next.andRespond(
                withSuccess(kind.equals("json") ? "invalid-json" : "{}", MediaType.APPLICATION_JSON));
        else next.andRespond(withSuccess(page(kind.equals("offset") ? 0 : 1, kind.equals("total") ? 3 : 2,
                kind.equals("empty") ? null : kind.equals("duplicate") ? "0001" : "0002"), MediaType.APPLICATION_JSON));
        if (kind.equals("complete")) {
            assertThat(client.findByCpeName("cpe:fixture", MatchConfidence.HIGH))
                    .extracting(NvdClient.NvdCve::cveId).containsExactly("CVE-2026-0001", "CVE-2026-0002");
        } else {
            int expected = kind.equals("offset") || kind.equals("total") ? 2 : 1;
            assertThatThrownBy(() -> client.findByCpeName("cpe:fixture", MatchConfidence.HIGH))
                    .isInstanceOfSatisfying(NvdClient.IncompleteLookupException.class, e -> {
                        assertThat(e.findings()).hasSize(expected);
                        assertThat(e.findings().getFirst().cveId()).isEqualTo("CVE-2026-0001");
                    });
        }
        server.verify();
    }

    @Test void nextOffsetUsesReceivedRowsInsteadOfPageCapacity() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = client(builder);
        server.expect(requestTo(URL)).andRespond(withSuccess("""
                {"startIndex":0,"totalResults":3,"resultsPerPage":2000,"vulnerabilities":[
                  {"cve":{"id":"CVE-2026-0001"}},{"cve":{"id":"CVE-2026-0002"}}]}
                """, MediaType.APPLICATION_JSON));
        server.expect(requestTo(URL + "&startIndex=2"))
                .andRespond(withSuccess(page(2, 3, "0003"), MediaType.APPLICATION_JSON));
        assertThat(client.findByCpeName("cpe:fixture", MatchConfidence.HIGH))
                .extracting(NvdClient.NvdCve::cveId)
                .containsExactly("CVE-2026-0001", "CVE-2026-0002", "CVE-2026-0003");
        server.verify();
    }

    @Test void pageBudgetExhaustionPreservesAllReceivedFindings() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = client(builder);
        for (int i = 0; i < 20; i++) {
            server.expect(requestTo(URL + (i == 0 ? "" : "&startIndex=" + i)))
                    .andRespond(withSuccess(page(i, 21, String.valueOf(1000 + i)), MediaType.APPLICATION_JSON));
        }
        assertThatThrownBy(() -> client.findByCpeName("cpe:fixture", MatchConfidence.HIGH))
                .isInstanceOfSatisfying(NvdClient.IncompleteLookupException.class,
                        e -> assertThat(e.findings()).hasSize(20));
        server.verify();
    }

    private static NvdClient client(RestClient.Builder builder) {
        var client = new NvdClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        ReflectionTestUtils.setField(client, "minIntervalMs", 0L);
        return client;
    }

    private static String page(int start, int total, String suffix) {
        return "{\"startIndex\":" + start + ",\"totalResults\":" + total + ",\"resultsPerPage\":1,\"vulnerabilities\":"
                + (suffix == null ? "[]" : "[{\"cve\":{\"id\":\"CVE-2026-" + suffix + "\"}}]") + "}";
    }
}
