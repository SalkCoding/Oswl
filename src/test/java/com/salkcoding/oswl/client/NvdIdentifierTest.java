package com.salkcoding.oswl.client;

import com.salkcoding.oswl.domain.enums.MatchConfidence;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NvdIdentifierTest {
    @ParameterizedTest
    @ValueSource(strings = {"\"\"", "\" \"", "\"CVE-FIXTURE\"", "\"cve-2026-0001\"",
            "\"CVE-2026-123\"", "\"CVE-26-0001\"", "\" CVE-2026-0001\"",
            "\"CVE-2026-0001\\n\"", "\"CVE-２０２６-0001\"", "123", "null"})
    void malformedIdentifierCannotBecomeAConfirmedFinding(String idJson) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new NvdClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(anything()).andRespond(withSuccess("""
                {"startIndex":0,"resultsPerPage":3,"totalResults":3,"vulnerabilities":[
                  {"cve":{"id":"CVE-2026-0002"}},
                  {"cve":{"id":%s}},
                  {"cve":{"id":"CVE-2026-0003"}}]}
                """.formatted(idJson), MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.findByCpeName("cpe:fixture", MatchConfidence.HIGH))
                .isInstanceOfSatisfying(NvdClient.IncompleteLookupException.class,
                        e -> assertThat(e.findings()).extracting(NvdClient.NvdCve::cveId)
                                .containsExactly("CVE-2026-0002", "CVE-2026-0003"));
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"CVE-1999-0001", "CVE-2026-12345", "CVE-2026-12345678901234567890"})
    void identifiersAcceptedByTheProviderSchemaRemainUnchanged(String id) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new NvdClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(anything()).andRespond(withSuccess("""
                {"startIndex":0,"resultsPerPage":1,"totalResults":1,"vulnerabilities":[{"cve":{"id":"%s"}}]}
                """.formatted(id), MediaType.APPLICATION_JSON));
        assertThat(client.findByCpeName("cpe:fixture", MatchConfidence.HIGH))
                .extracting(NvdClient.NvdCve::cveId).containsExactly(id);
        server.verify();
    }
}
