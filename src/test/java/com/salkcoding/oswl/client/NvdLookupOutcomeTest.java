package com.salkcoding.oswl.client;

import com.salkcoding.oswl.domain.enums.*;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class NvdLookupOutcomeTest {
    @Test void successfulEmptyMalformedAndServerFailureAreDistinct() {
        for (String body : new String[]{"{\"startIndex\":0,\"resultsPerPage\":2000,\"totalResults\":0,\"vulnerabilities\":[]}", "{}", "{\"vulnerabilities\":[null]}", "{\"startIndex\":0,\"resultsPerPage\":2000,\"totalResults\":2,\"vulnerabilities\":[]}"}) {
            var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
            var client = new NvdClient(); ReflectionTestUtils.setField(client, "restClient", builder.build());
            server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            if (body.contains("\"totalResults\":0")) assertThat(client.findByCpeName("cpe:fixture", MatchConfidence.HIGH)).isEmpty();
            else assertThatThrownBy(() -> client.findByCpeName("cpe:fixture", MatchConfidence.HIGH)).hasMessage("NVD lookup unavailable");
            server.verify();
        }
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var client = new NvdClient(); ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(anything()).andRespond(withServerError());
        assertThatThrownBy(() -> client.findByCpeName("cpe:fixture", MatchConfidence.HIGH)).hasMessage("NVD lookup unavailable");
    }

    @Test void versionFourMetricsEnterTheSharedVectorAndSeverityPipeline() {
        String vector = "CVSS:4.0/AV:N/AC:L/AT:N/PR:N/UI:N/VC:H/VI:H/VA:H/SC:N/SI:N/SA:N";
        var builder = RestClient.builder(); var server = MockRestServiceServer.bindTo(builder).build();
        var client = new NvdClient(); ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(anything()).andRespond(withSuccess("{\"startIndex\":0,\"resultsPerPage\":2000,\"totalResults\":1,\"vulnerabilities\":[{\"cve\":{\"id\":\"CVE-2026-0001\",\"metrics\":{\"cvssMetricV40\":[{\"cvssData\":{\"baseScore\":9.3,\"vectorString\":\"" + vector + "\"}}]}}}]}", MediaType.APPLICATION_JSON));
        var result = client.findByCpeName("cpe:fixture", MatchConfidence.HIGH).getFirst();
        assertThat(result.cvss3Vector()).isEqualTo(vector);
        assertThat(result.severity()).isEqualTo(RiskLevel.CRITICAL);
        assertThat(result.cvssScore()).isEqualTo(9.3);
    }
}
