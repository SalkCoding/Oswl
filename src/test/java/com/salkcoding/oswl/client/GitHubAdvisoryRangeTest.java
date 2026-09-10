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
    @CsvSource({"MODERATE,false,MEDIUM", "UNKNOWN,true,CRITICAL"})
    void preservesCurrentSeverityFields(String severity, boolean scored, String expected) throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new GitHubAdvisoryClient(null, false, "fixture", "https://api.github.com",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var response = mapper.readTree(page("GHSA-fixture", false, "end"));
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) response.path("data").path("securityVulnerabilities").path("nodes").get(0);
        node.put("severity", severity);
        String vector = "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H";
        var scores = ((com.fasterxml.jackson.databind.node.ObjectNode) node.path("advisory")).putObject("cvssSeverities");
        if (scored) scores.putObject("cvssV3").put("score", 9.8).put("vectorString", vector);
        else scores.putNull("cvssV3");
        server.expect(requestTo("https://api.github.com/graphql")).andRespond(withSuccess(mapper.writeValueAsString(response), MediaType.APPLICATION_JSON));
        var result = new GitHubAdvisorySource(client).lookup("npm", "fixture", "1.0.0", List.of());
        assertThat(result.lookupFailed()).isFalse();
        var finding = result.findings().getFirst();
        assertThat(finding.severity().name()).isEqualTo(expected);
        assertThat(finding.cvssScore()).isEqualTo(scored ? 9.8 : null);
        assertThat(finding.cvss3Vector()).isEqualTo(scored ? vector : null);
        server.verify();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"active", "withdrawn", "invalid-date", "wrong-name", "wrong-ecosystem", "missing-package"})
    void respectsIdentityAndWithdrawal(String condition) throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new GitHubAdvisoryClient(null, false, "fixture", "https://api.github.com",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var response = mapper.readTree(page("GHSA-fixture", false, "end"));
        var node = (com.fasterxml.jackson.databind.node.ObjectNode) response.path("data").path("securityVulnerabilities").path("nodes").get(0);
        node.putObject("package").put("name", condition.equals("wrong-name") ? "other" : "fixture")
                .put("ecosystem", condition.equals("wrong-ecosystem") ? "PIP" : "NPM");
        if (condition.equals("missing-package")) node.remove("package");
        var advisory = (com.fasterxml.jackson.databind.node.ObjectNode) node.path("advisory");
        if (condition.equals("withdrawn")) advisory.put("withdrawnAt", "2026-01-01T00:00:00Z");
        else if (condition.equals("invalid-date")) advisory.put("withdrawnAt", "not-a-date");
        else advisory.putNull("withdrawnAt");
        server.expect(requestTo("https://api.github.com/graphql")).andRespond(withSuccess(mapper.writeValueAsString(response), MediaType.APPLICATION_JSON));
        var result = new GitHubAdvisorySource(client).lookup("npm", "fixture", "1.0.0", List.of());
        assertThat(result.lookupFailed()).isEqualTo(!condition.equals("active") && !condition.equals("withdrawn"));
        assertThat(result.findings()).hasSize(condition.equals("active") ? 1 : 0);
        server.verify();
    }

    @org.junit.jupiter.api.Test
    void pageBudgetStopsRequestsWithoutClaimingCompletion() throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new GitHubAdvisoryClient(null, false, "fixture", "https://api.github.com",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        for (int index = 0; index < 10; index++) {
            server.expect(requestTo("https://api.github.com/graphql")).andRespond(withSuccess(
                    page("GHSA-" + index, true, "cursor-" + index), MediaType.APPLICATION_JSON));
        }
        var result = new GitHubAdvisorySource(client).lookup("npm", "fixture", "1.0.0", List.of());
        assertThat(result.lookupFailed()).isTrue();
        assertThat(result.findings()).hasSize(10);
        server.verify();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"complete", "http-failure", "repeated-cursor"})
    void followsCursorAndPreservesEarlierPagesOnFailure(String outcome) throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new GitHubAdvisoryClient(null, false, "fixture", "https://api.github.com",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(requestTo("https://api.github.com/graphql")).andRespond(withSuccess(page("GHSA-first", true, "cursor-one"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.github.com/graphql"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().string(
                        org.hamcrest.Matchers.containsString("\"after\":\"cursor-one\"")))
                .andRespond(outcome.equals("http-failure")
                        ? org.springframework.test.web.client.response.MockRestResponseCreators.withServerError()
                        : withSuccess(page("GHSA-second", outcome.equals("repeated-cursor"), "cursor-one"), MediaType.APPLICATION_JSON));
        var result = new GitHubAdvisorySource(client).lookup("npm", "fixture", "1.0.0", List.of());
        assertThat(result.lookupFailed()).isEqualTo(!outcome.equals("complete"));
        assertThat(result.findings()).extracting(GitHubAdvisoryClient.GitHubAdvisory::ghsaId)
                .containsExactlyElementsOf(outcome.equals("http-failure") ? List.of("GHSA-first") : List.of("GHSA-first", "GHSA-second"));
        server.verify();
    }

    private String page(String id, boolean more, String cursor) throws Exception {
        return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(Map.of("data", Map.of("securityVulnerabilities",
                Map.of("pageInfo", Map.of("hasNextPage", more, "endCursor", cursor), "nodes", List.of(Map.of(
                        "package", Map.of("name", "fixture", "ecosystem", "NPM"),
                        "vulnerableVersionRange", "< 2.0.0", "advisory", Map.of("identifiers", List.of(Map.of("type", "GHSA", "value", id)))))))));
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"bad-first", "bad-last", "pagination", "graphql-error", "missing-page-info"})
    void incompleteLookupRetainsConfirmedFindings(String failure) throws Exception {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new GitHubAdvisoryClient(null, false, "fixture", "https://api.github.com",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        var valid = Map.of("package", Map.of("name", "fixture", "ecosystem", "NPM"), "vulnerableVersionRange", "< 2.0.0", "advisory",
                Map.of("identifiers", List.of(Map.of("type", "GHSA", "value", "GHSA-confirmed"))));
        List<?> nodes = switch (failure) {
            case "bad-first" -> List.of(Map.of(), valid);
            case "bad-last" -> List.of(valid, Map.of());
            default -> List.of(valid);
        };
        var connection = new java.util.LinkedHashMap<String, Object>();
        connection.put("nodes", nodes);
        if (!failure.equals("missing-page-info")) connection.put("pageInfo", Map.of("hasNextPage", failure.equals("pagination")));
        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("data", Map.of("securityVulnerabilities", connection));
        if (failure.equals("graphql-error")) response.put("errors", List.of(Map.of("message", "partial data")));
        server.expect(requestTo("https://api.github.com/graphql")).andRespond(withSuccess(
                new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(response), MediaType.APPLICATION_JSON));
        var result = new GitHubAdvisorySource(client).lookup("npm", "fixture", "1.0.0", List.of());
        assertThat(result.lookupFailed()).isTrue();
        assertThat(result.findings()).extracting(GitHubAdvisoryClient.GitHubAdvisory::ghsaId).containsExactly("GHSA-confirmed");
        server.verify();
    }

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
        node.put("package", Map.of("name", "fixture", "ecosystem", "NPM"));
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
