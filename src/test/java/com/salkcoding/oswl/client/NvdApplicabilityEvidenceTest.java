package com.salkcoding.oswl.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.domain.enums.MatchConfidence;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NvdApplicabilityEvidenceTest {
    @ParameterizedTest @ValueSource(strings = {"tree", "empty", "null", "missing"})
    void retainsConfigurationContextWithoutClaimingApplicability(String state) throws Exception {
        var json = new ObjectMapper();
        var cve = json.createObjectNode().put("id","CVE-2026-123450")
                .put("sourceIdentifier","fixture@example.invalid").put("lastModified","2026-09-12T00:00:00.000")
                .put("vulnStatus","Analyzed");
        if (!state.equals("missing")) cve.set("configurations",json.readTree(switch(state) {
            case "empty" -> "[]";
            case "null" -> "null";
            default -> "[{\"operator\":\"AND\",\"nodes\":[{\"operator\":\"OR\",\"negate\":false,\"cpeMatch\":[{\"vulnerable\":false,\"criteria\":\"cpe:2.3:o:fixture:os:*:*:*:*:*:*:x64:*\"},{\"vulnerable\":true,\"criteria\":\"cpe:2.3:a:fixture:product:*:*:*:*:*:*:*:*\",\"versionEndExcluding\":\"2.0\"}]}]}]";
        }));
        var builder=RestClient.builder();
        var server=MockRestServiceServer.bindTo(builder).build();
        var client=new NvdClient();
        ReflectionTestUtils.setField(client,"restClient",builder.build());
        ReflectionTestUtils.setField(client,"minIntervalMs",0L);
        server.expect(anything()).andRespond(withSuccess(json.writeValueAsString(Map.of("startIndex",0,
                "resultsPerPage",1,"totalResults",1,"vulnerabilities",java.util.List.of(Map.of("cve",cve)))),MediaType.APPLICATION_JSON));
        var result=client.findByCpeName("cpe:2.3:a:fixture:product:1:*:*:*:*:*:*:*",MatchConfidence.HIGH).getFirst();
        assertThat(json.readTree(result.nvdApplicability())).isEqualTo(cve);
        assertThat(json.readTree(result.nvdApplicability()).has("configurations")).isEqualTo(!state.equals("missing"));
        assertThat(result.matchConfidence()).isEqualTo(MatchConfidence.HIGH);
        server.verify();
    }
}
