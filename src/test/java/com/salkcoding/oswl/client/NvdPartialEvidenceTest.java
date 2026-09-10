package com.salkcoding.oswl.client;

import com.salkcoding.oswl.domain.enums.MatchConfidence;
import com.salkcoding.oswl.service.vulnerability.sources.NvdAdvisorySource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NvdPartialEvidenceTest {
    @ParameterizedTest
    @ValueSource(strings = {"page", "before", "after"})
    void incompleteCandidateKeepsFindingsAndDoesNotSkipTheNextCandidate(String kind) {
        String good = "{\"cve\":{\"id\":\"CVE-2026-0001\"}}";
        String rows = switch (kind) {
            case "before" -> "null," + good;
            case "after" -> good + ",null";
            default -> good;
        };
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new NvdClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        ReflectionTestUtils.setField(client, "minIntervalMs", 0L);
        server.expect(anything()).andRespond(withSuccess("{\"totalResults\":2,\"vulnerabilities\":[" + rows + "]}", MediaType.APPLICATION_JSON));
        server.expect(anything()).andRespond(withSuccess("{\"totalResults\":1,\"vulnerabilities\":[{\"cve\":{\"id\":\"CVE-2026-0002\"}}]}", MediaType.APPLICATION_JSON));
        var cpe = mock(CpeMatchService.class);
        when(cpe.inferCpes("fixture", "1.0")).thenReturn(List.of(
                new CpeNameMapper.CpeCandidate("vendor", "one", "1.0", MatchConfidence.HIGH),
                new CpeNameMapper.CpeCandidate("vendor", "two", "1.0", MatchConfidence.LOW)));
        var result = new NvdAdvisorySource(client, cpe).lookup("fixture", "1.0", null, List.of());
        assertThat(result.lookupFailed()).isTrue();
        assertThat(result.findings()).extracting(NvdClient.NvdCve::cveId)
                .containsExactly("CVE-2026-0001", "CVE-2026-0002");
        assertThat(result.findings()).extracting(NvdClient.NvdCve::matchConfidence)
                .containsExactly(MatchConfidence.HIGH, MatchConfidence.LOW);
        server.verify();
    }
}
