package com.salkcoding.oswl.client;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class EpssClientTest {
    @ParameterizedTest
    @ValueSource(strings = {"NaN", "Infinity", "-Infinity", "-0.1", "1.1", "broken", "0", "0.5", "1"})
    void validatesScoresWhilePreservingOtherRows(String score) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new EpssClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(requestTo("https://api.first.org/data/v1/epss?cve=CVE-2026-0001,CVE-2026-0002"))
                .andRespond(withSuccess("{\"data\":[{\"cve\":\"CVE-2026-0001\",\"epss\":\"" + score
                        + "\"},{\"cve\":\"CVE-2026-0002\",\"epss\":\"0.5\"}]}", MediaType.APPLICATION_JSON));
        var expected = new java.util.LinkedHashMap<String, Double>();
        expected.put("CVE-2026-0002", 0.5);
        if (List.of("0", "0.5", "1").contains(score)) expected.put("CVE-2026-0001", Double.parseDouble(score));
        assertThat(client.fetchScores(List.of("CVE-2026-0001", "CVE-2026-0002"))).isEqualTo(expected);
        server.verify();
    }
}
