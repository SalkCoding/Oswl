package com.salkcoding.oswl.client;

import com.salkcoding.oswl.service.vulnerability.sources.GitHubAdvisorySource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubAdvisoryRangeTest {
    @ParameterizedTest
    @CsvSource(value = {
            "1.0.0;null;true;0", "null;< 2.0.0;true;0", "1.0.0; ;true;0",
            "1.0.0;>= 0.0.0,;true;0", "1.0.0;< 0.5.0, nonsense;true;0",
            "1.0.0;< 2.0.0 || >= 3.0.0;true;0",
            "1.0.0-alpha;< 1.0.0;false;1", "1.0.0+build2;= 1.0.0+build1;false;1",
            "1.0.0;>= 1.0.0, < 2.0.0;false;1", "2.0.0;>= 1.0.0, < 2.0.0;false;0"
    }, delimiter = ';', nullValues = "null")
    void sourcePreservesUnknownAndUsesNpmVersionPrecedence(String version, String range, boolean failed, int findings) throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new GitHubAdvisoryClient(null, false, "fixture", "https://api.github.com",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        var node = new java.util.LinkedHashMap<String, Object>();
        node.put("vulnerableVersionRange", range);
        node.put("advisory", Map.of("identifiers", List.of(Map.of("type", "GHSA", "value", "GHSA-fixture"))));
        String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("data",
                Map.of("securityVulnerabilities", Map.of("pageInfo", Map.of("hasNextPage", false), "nodes", List.of(node)))));
        server.expect(requestTo("https://api.github.com/graphql")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
        var result = new GitHubAdvisorySource(client).lookup("npm", "fixture", version, List.of());
        assertThat(result.lookupFailed()).isEqualTo(failed);
        assertThat(result.findings()).hasSize(findings);
        server.verify();
    }
}
