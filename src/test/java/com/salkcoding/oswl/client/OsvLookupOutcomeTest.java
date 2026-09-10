package com.salkcoding.oswl.client;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.util.Map;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OsvLookupOutcomeTest {
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.MethodSource("incompleteQueries")
    void incompleteIdentityRemainsUnresolvedInBothModes(OsvClient.OsvQuery incomplete) {
        var builder = RestClient.builder().baseUrl("https://api.osv.dev");
        var server = MockRestServiceServer.bindTo(builder).build();
        var online = new OsvClient();
        ReflectionTestUtils.setField(online, "restClient", builder.build());
        server.expect(requestTo("https://api.osv.dev/v1/querybatch"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json(
                        "{\"queries\":[{\"package\":{\"ecosystem\":\"npm\",\"name\":\"valid\"},\"version\":\"1.0.0\"}]}"))
                .andRespond(withSuccess("{\"results\":[{}]}", MediaType.APPLICATION_JSON));
        String validKey = AirgappedSnapshotService.componentKey("npm", "valid", "1.0.0");
        var snapshot = mock(AirgappedSnapshotService.class);
        when(snapshot.findOsvVulns(anyCollection())).thenAnswer(invocation -> {
            java.util.Collection<String> keys = invocation.getArgument(0);
            assertThat(keys).containsExactly(validKey);
            return Map.of(validKey, List.of());
        });
        var queries = java.util.Arrays.asList(incomplete, new OsvClient.OsvQuery("npm", "valid", "1.0.0"));

        for (var client : List.of(online, new OsvClient(snapshot, true))) {
            var actual = client.queryBatch(queries);
            assertThat(actual).hasSize(2);
            assertThat(actual.getFirst().resolved()).isFalse();
            assertThat(actual.getFirst().vulns()).isEmpty();
            assertThat(actual.getLast().resolved()).isTrue();
        }
        server.verify();
    }

    static java.util.stream.Stream<OsvClient.OsvQuery> incompleteQueries() {
        return java.util.stream.Stream.of(null,
                new OsvClient.OsvQuery("npm", "valid", ""),
                new OsvClient.OsvQuery("npm", "valid", " \t"),
                new OsvClient.OsvQuery("npm", "", "1.0.0"),
                new OsvClient.OsvQuery("npm", " \t", "1.0.0"),
                new OsvClient.OsvQuery("", "valid", "1.0.0"),
                new OsvClient.OsvQuery(" \t", "valid", "1.0.0"));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"[]", "[{}]", "[{},{},{}]"})
    void mismatchedBatchCardinalityCannotConfirmAnyPackageIsClean(String results) {
        var builder = RestClient.builder().baseUrl("https://api.osv.dev");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new OsvClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(requestTo("https://api.osv.dev/v1/querybatch")).andRespond(withSuccess(
                "{\"results\":" + results + "}", MediaType.APPLICATION_JSON));

        var actual = client.queryBatch(List.of(new OsvClient.OsvQuery("npm", "a", "1.0.0"),
                new OsvClient.OsvQuery("npm", "b", "1.0.0")));

        assertThat(actual).hasSize(2).allMatch(result -> !result.resolved() && result.vulns().isEmpty());
        server.verify();
    }

    @Test void responseCardinalityUsesOnlyQueriesActuallySent() {
        var builder = RestClient.builder().baseUrl("https://api.osv.dev");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new OsvClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(requestTo("https://api.osv.dev/v1/querybatch"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json(
                        "{\"queries\":[{\"package\":{\"ecosystem\":\"npm\",\"name\":\"a\"},\"version\":\"1.0.0\"}]}"))
                .andRespond(withSuccess("{\"results\":[{}]}", MediaType.APPLICATION_JSON));

        var actual = client.queryBatch(List.of(new OsvClient.OsvQuery("npm", "missing", null),
                new OsvClient.OsvQuery("npm", "a", "1.0.0")));

        assertThat(actual).hasSize(2);
        assertThat(actual.getFirst().resolved()).isFalse();
        assertThat(actual.getLast().resolved()).isTrue();
        server.verify();
    }

    @Test void aMalformedBatchDoesNotInvalidateTheNextBatch() {
        var builder = RestClient.builder().baseUrl("https://api.osv.dev");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new OsvClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(requestTo("https://api.osv.dev/v1/querybatch"))
                .andRespond(withSuccess("{\"results\":[{}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.osv.dev/v1/querybatch"))
                .andRespond(withSuccess("{\"results\":[{}]}", MediaType.APPLICATION_JSON));
        var queries = java.util.stream.IntStream.range(0, 1001)
                .mapToObj(i -> new OsvClient.OsvQuery("npm", "fixture-" + i, "1.0.0")).toList();

        var actual = client.queryBatch(queries);

        assertThat(actual).hasSize(1001);
        assertThat(actual.subList(0, 1000)).allMatch(result -> !result.resolved());
        assertThat(actual.getLast().resolved()).isTrue();
        server.verify();
    }

    @Test void withdrawingOneAdvisoryDoesNotWithdrawAnActiveAlias() {
        var builder = RestClient.builder().baseUrl("https://api.osv.dev");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new OsvClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(requestTo("https://api.osv.dev/v1/querybatch")).andRespond(withSuccess(
                "{\"results\":[{\"vulns\":[{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"OSV-withdrawn\"},{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"OSV-active\"}]}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.osv.dev/v1/vulns/OSV-withdrawn")).andRespond(withSuccess(
                "{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"OSV-withdrawn\",\"withdrawn\":\"2026-01-01T00:00:00Z\",\"aliases\":[\"CVE-2026-0001\"]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.osv.dev/v1/vulns/OSV-active")).andRespond(withSuccess(
                "{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"OSV-active\",\"aliases\":[\"CVE-2026-0001\"]," +
                        "\"affected\":[{\"package\":{\"ecosystem\":\"npm\",\"name\":\"example\"},\"versions\":[\"1.0.0\"]}]}", MediaType.APPLICATION_JSON));
        var result = client.queryBatch(List.of(new OsvClient.OsvQuery("npm", "example", "1.0.0"))).getFirst();
        assertThat(result.resolved()).isTrue();
        assertThat(result.vulns()).hasSize(1);
        assertThat(result.vulns().getFirst().osvId()).isEqualTo("OSV-active");
        server.verify();
    }
    @Test void withdrawalFromHydratedDetailControlsActiveFindings() {
        for (String withdrawal : List.of("\"2026-01-01T00:00:00Z\"", "true", "null", "\"invalid\"")) {
            var builder = RestClient.builder().baseUrl("https://api.osv.dev");
            var server = MockRestServiceServer.bindTo(builder).build();
            var client = new OsvClient();
            ReflectionTestUtils.setField(client, "restClient", builder.build());
            server.expect(requestTo("https://api.osv.dev/v1/querybatch")).andRespond(withSuccess(
                    "{\"results\":[{\"vulns\":[{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"OSV-withdrawn\"}]}]}", MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://api.osv.dev/v1/vulns/OSV-withdrawn")).andRespond(withSuccess(
                    "{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"OSV-withdrawn\",\"withdrawn\":" + withdrawal + "}", MediaType.APPLICATION_JSON));
            var result = client.queryBatch(List.of(new OsvClient.OsvQuery("npm", "example", "1.0.0"))).getFirst();
            assertThat(result.vulns()).as(withdrawal).isEmpty();
            assertThat(result.resolved()).as(withdrawal).isEqualTo(withdrawal.startsWith("\"2026"));
            server.verify();
        }
    }
    @Test void missingOfflineDataDoesNotBecomeASuccessfulEmptyLookup() {
        var snapshot = mock(AirgappedSnapshotService.class);
        String key = AirgappedSnapshotService.componentKey("PyPI", "present", "1");
        when(snapshot.findOsvVulns(anyCollection())).thenReturn(Map.of(key, List.of()));
        var results = new OsvClient(snapshot, true).queryBatch(List.of(
                new OsvClient.OsvQuery("PyPI", "present", "1"),
                new OsvClient.OsvQuery("PyPI", "missing", "1")));
        assertThat(results.get(0).resolved()).isTrue();
        assertThat(results.get(1).resolved()).isFalse();
    }

    @Test void emptySuccessFailureAndMalformedResponsesRemainDistinct() {
        for (String body : List.of("{\"results\":[{}]}", "{}", "{\"results\":null}",
                "{\"results\":[null]}", "{\"results\":[{\"vulns\":false}]}",
                "{\"results\":[{\"vulns\":[null]}]}", "{\"results\":[{\"vulns\":[{}]}]}",
                "{\"results\":[{\"next_page_token\":\"more\"}]}")) {
            var builder = RestClient.builder().baseUrl("https://api.osv.dev");
            var server = MockRestServiceServer.bindTo(builder).build();
            OsvClient client = new OsvClient();
            ReflectionTestUtils.setField(client, "restClient", builder.build());
            server.expect(requestTo("https://api.osv.dev/v1/querybatch"))
                    .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
            if (body.contains("next_page_token")) {
                server.expect(requestTo("https://api.osv.dev/v1/querybatch")).andRespond(withServerError());
            }
            var result = client.queryBatch(List.of(new OsvClient.OsvQuery("PyPI", "fixture", "1"))).getFirst();
            assertThat(result.resolved()).as(body).isEqualTo(body.equals("{\"results\":[{}]}"));
            server.verify();
        }
        var builder = RestClient.builder().baseUrl("https://api.osv.dev");
        var server = MockRestServiceServer.bindTo(builder).build();
        OsvClient client = new OsvClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(requestTo("https://api.osv.dev/v1/querybatch")).andRespond(withServerError());
        assertThat(client.queryBatch(List.of(new OsvClient.OsvQuery("PyPI", "fixture", "1"))).getFirst().resolved()).isFalse();
        server.verify();
    }
    @Test void batchIdsAreHydratedOnceAndDetailFailureRetainsIncompleteFinding() {
        for (boolean success : List.of(true, false)) {
            var builder = RestClient.builder().baseUrl("https://api.osv.dev");
            var server = MockRestServiceServer.bindTo(builder).build();
            var client = new OsvClient(); ReflectionTestUtils.setField(client, "restClient", builder.build());
            server.expect(requestTo("https://api.osv.dev/v1/querybatch")).andRespond(withSuccess(
                    "{\"results\":[{\"vulns\":[{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"GHSA-fixture\"}]},{\"vulns\":[{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"GHSA-fixture\"}]}]}", MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://api.osv.dev/v1/vulns/GHSA-fixture")).andRespond(success ? withSuccess(
                    "{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"GHSA-fixture\",\"aliases\":[\"CVE-2026-0001\"],\"severity\":[{\"type\":\"CVSS_V3\",\"score\":\"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H\"}]," +
                            "\"affected\":[{\"package\":{\"ecosystem\":\"npm\",\"name\":\"a\"},\"versions\":[\"1\"]}," +
                            "{\"package\":{\"ecosystem\":\"npm\",\"name\":\"b\"},\"versions\":[\"1\"]}]}", MediaType.APPLICATION_JSON) : withServerError());
            var result = client.queryBatch(List.of(new OsvClient.OsvQuery("npm", "a", "1"), new OsvClient.OsvQuery("npm", "b", "1")));
            assertThat(result).allMatch(r -> r.resolved() == success && r.vulns().size() == 1);
            if (success) {
                assertThat(result.getFirst().vulns().getFirst().effectiveSeverity()).isEqualTo(com.salkcoding.oswl.domain.enums.RiskLevel.CRITICAL);
                assertThat(result.getFirst().vulns().getFirst().cvssScore()).isEqualTo(9.8);
            }
            server.verify();
        }
    }

}
