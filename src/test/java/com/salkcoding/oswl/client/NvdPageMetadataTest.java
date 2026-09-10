package com.salkcoding.oswl.client;

import com.salkcoding.oswl.domain.enums.MatchConfidence;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NvdPageMetadataTest {
    @ParameterizedTest
    @CsvSource(value = {"0;0;0;0;true", "1;0;1;1;true", "1;0;2000;1;true",
            "MISSING;0;0;0;false", "null;0;0;0;false", "-1;0;0;0;false", "0.5;0;0;0;false",
            "\"0\";0;0;0;false", "1;MISSING;1;1;false", "1;1;1;1;false", "1;-1;1;1;false",
            "1;0;MISSING;1;false", "1;0;0;1;false", "1;0;1.5;1;false", "0;0;1;1;false",
            "2;0;1;1;false"}, delimiter = ';')
    void onlyConsistentPageMetadataCanConfirmCoverage(String total, String start, String size, int rows, boolean complete) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new NvdClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        ReflectionTestUtils.setField(client, "minIntervalMs", 0L);
        String body = "{" + field("totalResults", total) + field("startIndex", start) + field("resultsPerPage", size)
                + "\"vulnerabilities\":" + (rows == 0 ? "[]" : "[{\"cve\":{\"id\":\"CVE-2026-0001\"}}]") + "}";
        server.expect(anything()).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        if (total.equals("2")) server.expect(anything()).andRespond(
                org.springframework.test.web.client.response.MockRestResponseCreators.withServerError());
        if (complete) assertThat(client.findByCpeName("cpe:fixture", MatchConfidence.HIGH)).hasSize(rows);
        else assertThatThrownBy(() -> client.findByCpeName("cpe:fixture", MatchConfidence.HIGH))
                .isInstanceOfSatisfying(NvdClient.IncompleteLookupException.class,
                        e -> assertThat(e.findings()).hasSize(rows));
        server.verify();
    }

    private static String field(String key, String value) {
        return value.equals("MISSING") ? "" : "\"" + key + "\":" + value + ",";
    }
}
