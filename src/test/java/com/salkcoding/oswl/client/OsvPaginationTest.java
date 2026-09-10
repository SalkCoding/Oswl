package com.salkcoding.oswl.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class OsvPaginationTest {
    @ParameterizedTest
    @ValueSource(strings = {"changed", "reversed", "withdrawn", "legacy", "metadata", "identical"})
    void offlineDuplicateRevisionsDoNotAuthorizeIndividualFixes(String state) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var original = json.readTree("""
                {"id":"OSV-duplicate","modified":"2026-01-01T00:00:00Z","affected":[{
                "package":{"ecosystem":"npm","name":"example"},"ranges":[{"type":"SEMVER",
                "events":[{"introduced":"0"},{"fixed":"2.0.0"}]}]}]}
                """);
        var other = original.deepCopy();
        if (!state.equals("identical") && !state.equals("metadata")) ((com.fasterxml.jackson.databind.node.ObjectNode) other).put("modified", "2026-01-02T00:00:00Z");
        if (state.equals("withdrawn")) ((com.fasterxml.jackson.databind.node.ObjectNode) other).put("withdrawn", "2026-01-02T00:00:00Z");
        var first = new com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln(
                "OSV-duplicate", null, null, "2.0.0", null, null, null, null, null, java.util.Set.of(), original);
        var second = new com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln(
                "OSV-duplicate", null, null, "2.0.0", null, null, null, null, null,
                state.equals("metadata") ? java.util.Set.of("2.0.0", "3.0.0") : java.util.Set.of(), state.equals("legacy") ? null : other);
        var independentRaw = ((com.fasterxml.jackson.databind.node.ObjectNode) original.deepCopy()).put("id", "OSV-independent");
        var independent = new com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln(
                "OSV-independent", null, null, "2.0.0", null, null, null, null, null, java.util.Set.of(), independentRaw);
        var snapshots = org.mockito.Mockito.mock(com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.class);
        ReflectionTestUtils.setField(client, "airgapped", true);
        ReflectionTestUtils.setField(client, "snapshotService", snapshots);
        String key = com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.componentKey("npm", "example", "1.0.0");
        org.mockito.Mockito.when(snapshots.findOsvVulns(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Map.of(key,
                state.equals("reversed") ? List.of(second, first, independent) : List.of(first, second, independent)));
        var result = client.queryBatch(List.of(query())).getFirst();
        assertThat(result.resolved()).isEqualTo(state.equals("identical"));
        assertThat(result.vulns()).hasSize(2);
        assertThat(result.vulns()).filteredOn(v -> v.osvId().equals("OSV-duplicate")).singleElement().satisfies(v -> {
            assertThat(v.osvId()).isEqualTo("OSV-duplicate");
            assertThat(v.fixVersion()).isEqualTo(state.equals("identical") ? "2.0.0" : null);
        });
        assertThat(result.vulns()).filteredOn(v -> v.osvId().equals("OSV-independent")).singleElement()
                .satisfies(v -> assertThat(v.fixVersion()).isEqualTo("2.0.0"));
        assertThat(result.commonFix().version()).isEqualTo(state.equals("identical") ? "2.0.0" : null);
        server.verify();
    }
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.osv.dev");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OsvClient client = new OsvClient();

    OsvPaginationTest() {
        ReflectionTestUtils.setField(client, "restClient", builder.build());
    }

    @ParameterizedTest
    @ValueSource(strings = {"complete", "failure", "reintroduced", "revision"})
    void commonFixUsesAllPagesAndRequiresCompleteConsistentEvidence(String state) {
        batch("{\"results\":[{\"vulns\":[{\"id\":\"OSV-first\",\"modified\":\"2026-01-01T00:00:00Z\"}],\"next_page_token\":\"cursor\"}]}");
        fixDetail("OSV-first", "2.0.0", state.equals("reintroduced") ? ",{\"introduced\":\"2.5.0\"}" : "");
        if (state.equals("failure")) {
            server.expect(requestTo("https://api.osv.dev/v1/querybatch")).andRespond(withServerError());
        } else {
            String modified = state.equals("revision") ? "2026-01-02T00:00:00Z" : "2026-01-01T00:00:00Z";
            batch("{\"results\":[{\"vulns\":[{\"id\":\"OSV-second\",\"modified\":\"" + modified + "\"}]}]}");
            fixDetail("OSV-second", "3.0.0", "");
        }
        var result = client.queryBatch(List.of(query())).getFirst();
        assertThat(result.vulns().getFirst().fixVersion()).isEqualTo("2.0.0");
        if (state.equals("complete")) {
            assertThat(result.commonFix().version()).isEqualTo("3.0.0");
        } else {
            assertThat(result.commonFix().version()).isNull();
        }
        assertThat(result.resolved()).isEqualTo(state.equals("complete") || state.equals("reintroduced"));
        if (!result.resolved()) assertThat(result.commonFix().reason()).isEqualTo("INCOMPLETE_LOOKUP");
        server.verify();
    }

    private void fixDetail(String id, String fixed, String laterEvents) {
        server.expect(requestTo("https://api.osv.dev/v1/vulns/" + id))
                .andRespond(withSuccess("""
                        {"id":"%s","modified":"2026-01-01T00:00:00Z","affected":[{
                        "package":{"ecosystem":"npm","name":"example"},"ranges":[{"type":"SEMVER",
                        "events":[{"introduced":"0"},{"fixed":"%s"}%s]}]}]}
                        """.formatted(id, fixed, laterEvents), MediaType.APPLICATION_JSON));
    }

    @Test void candidateCannotEnterARangeExcludedFromCurrentFindings() {
        batch("{\"results\":[{\"vulns\":[{\"id\":\"OSV-first\",\"modified\":\"2026-01-01T00:00:00Z\"},{\"id\":\"OSV-later\",\"modified\":\"2026-01-01T00:00:00Z\"}]}]}");
        fixDetail("OSV-first", "2.0.0", "");
        fixDetail("OSV-later", "0.5.0", ",{\"introduced\":\"2.0.0\"},{\"fixed\":\"4.0.0\"}");
        var result = client.queryBatch(List.of(query())).getFirst();
        assertThat(result.resolved()).isTrue();
        assertThat(result.vulns()).extracting(OsvClient.OsvVuln::osvId).containsExactly("OSV-first");
        assertThat(result.commonFix().version()).isEqualTo("4.0.0");
        assertThat(result.commonFix().reason()).isEqualTo("SOURCE_FIXED_EVENT");
        server.verify();
    }

    @Test void offlineIndividualFixesCannotSubstituteForCommonRangeEvidence() {
        var snapshots = org.mockito.Mockito.mock(com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.class);
        ReflectionTestUtils.setField(client, "airgapped", true);
        ReflectionTestUtils.setField(client, "snapshotService", snapshots);
        String key = com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.componentKey("npm", "example", "1.0.0");
        org.mockito.Mockito.when(snapshots.findOsvVulns(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Map.of(key, List.of(
                new com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln("OSV-first", null, null, "2.0.0", null),
                new com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln("OSV-second", null, null, "3.0.0", null))));
        var result = client.queryBatch(List.of(query())).getFirst();
        assertThat(result.resolved()).isTrue();
        assertThat(result.vulns()).extracting(OsvClient.OsvVuln::fixVersion).containsExactly("2.0.0", "3.0.0");
        assertThat(result.commonFix().version()).isNull();
        assertThat(result.commonFix().reason()).isEqualTo("NO_RANGE_EVIDENCE");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"affected", "unaffected", "foreign", "withdrawn"})
    void offlineOriginalEvidenceRechecksMembershipAndFixInsteadOfTrustingProjectedFields(String state) throws Exception {
        var snapshots = org.mockito.Mockito.mock(com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.class);
        ReflectionTestUtils.setField(client, "airgapped", true);
        ReflectionTestUtils.setField(client, "snapshotService", snapshots);
        var raw = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"id":"OSV-original","modified":"2026-01-01T00:00:00Z","affected":[{"package":{"ecosystem":"npm","name":"%s"},
                "ranges":[{"type":"SEMVER","events":[{"introduced":"%s"},{"fixed":"4.0.0"}]}]}]}
                """.formatted(state.equals("foreign") ? "other" : "example", state.equals("unaffected") ? "2.0.0" : "0"));
        if (state.equals("withdrawn")) ((com.fasterxml.jackson.databind.node.ObjectNode) raw).put("withdrawn", "2026-01-01T00:00:00Z");
        var record = new com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln(
                "OSV-original", null, null, "99.0.0", null, null, null, null, null, java.util.Set.of(), raw);
        String key = com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.componentKey("npm", "example", "1.0.0");
        org.mockito.Mockito.when(snapshots.findOsvVulns(org.mockito.ArgumentMatchers.any())).thenReturn(java.util.Map.of(key, List.of(record)));
        var result = client.queryBatch(List.of(query())).getFirst();
        assertThat(result.resolved()).isEqualTo(!state.equals("foreign"));
        if (state.equals("affected")) {
            assertThat(result.vulns()).singleElement().satisfies(v -> assertThat(v.fixVersion()).isEqualTo("4.0.0"));
            assertThat(result.commonFix().version()).isEqualTo("4.0.0");
        } else {
            assertThat(result.vulns()).isEmpty();
            assertThat(result.commonFix().version()).isNull();
        }
        server.verify();
    }

    @Test void followsOnlyThePaginatedQueryAndPreservesInputAlignment() {
        batch("{\"results\":[{\"vulns\":[{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"OSV-one\"}],\"next_page_token\":\"cursor\"},{}]}");
        detail("OSV-one");
        server.expect(requestTo("https://api.osv.dev/v1/querybatch"))
                .andExpect(content().json("""
                        {"queries":[{"package":{"name":"example","ecosystem":"npm"},
                        "version":"1.0.0","page_token":"cursor"}]}
                        """))
                .andRespond(withSuccess("{\"results\":[{\"vulns\":[{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"OSV-one\"},{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"OSV-two\"}]}]}", MediaType.APPLICATION_JSON));
        detail("OSV-two");
        var results = client.queryBatch(List.of(query(), new OsvClient.OsvQuery("npm", "invalid", null),
                new OsvClient.OsvQuery("npm", "empty", "1.0.0")));
        assertThat(results.get(0).resolved()).isTrue();
        assertThat(results.get(0).vulns()).extracting(OsvClient.OsvVuln::osvId).containsExactly("OSV-one", "OSV-two");
        assertThat(results.get(1).resolved()).isFalse();
        assertThat(results.get(2).resolved()).isTrue();
        assertThat(results.get(2).vulns()).isEmpty();
        server.verify();
    }

    @Test void emptyFirstPageStillFollowsItsCursor() {
        batch("{\"results\":[{\"next_page_token\":\"cursor\"}]}");
        batch("{\"results\":[{\"vulns\":[{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"OSV-one\"}]}]}");
        detail("OSV-one");
        var result = client.queryBatch(List.of(query())).getFirst();
        assertThat(result.resolved()).isTrue();
        assertThat(result.vulns()).hasSize(1);
        server.verify();
    }

    @Test void laterHttpFailureRetainsEarlierFindings() {
        batch("{\"results\":[{\"vulns\":[{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"OSV-one\"}],\"next_page_token\":\"cursor\"}]}");
        detail("OSV-one");
        server.expect(requestTo("https://api.osv.dev/v1/querybatch")).andRespond(withServerError());
        var result = client.queryBatch(List.of(query())).getFirst();
        assertThat(result.resolved()).isFalse();
        assertThat(result.vulns()).extracting(OsvClient.OsvVuln::osvId).containsExactly("OSV-one");
        server.verify();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{\"results\":[{\"next_page_token\":\"cursor\"}]}",
            "{}", "{\"results\":[{},{}]}", "{\"results\":[{\"next_page_token\":123}]}",
            "{\"results\":[{\"vulns\":false}]}"})
    void repeatedCursorAndMalformedPagesRemainIncomplete(String page) {
        batch("{\"results\":[{\"vulns\":[{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"OSV-one\"}],\"next_page_token\":\"cursor\"}]}");
        detail("OSV-one");
        batch(page);
        var result = client.queryBatch(List.of(query())).getFirst();
        assertThat(result.resolved()).isFalse();
        assertThat(result.vulns()).hasSize(1);
        server.verify();
    }

    private void batch(String body) {
        server.expect(requestTo("https://api.osv.dev/v1/querybatch"))
                .andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    @Test void paginationStopsAtThePageBudgetWithoutClaimingCompleteCoverage() {
        batch("{\"results\":[{\"vulns\":[{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"OSV-one\"}],\"next_page_token\":\"cursor-1\"}]}");
        detail("OSV-one");
        for (int page = 2; page <= 10; page++) {
            batch("{\"results\":[{\"next_page_token\":\"cursor-" + page + "\"}]}");
        }
        var result = client.queryBatch(List.of(query())).getFirst();
        assertThat(result.resolved()).isFalse();
        assertThat(result.vulns()).hasSize(1);
        server.verify();
    }

    private void detail(String id) {
        server.expect(requestTo("https://api.osv.dev/v1/vulns/" + id))
                .andRespond(withSuccess("{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"" + id + "\"," +
                        "\"affected\":[{\"package\":{\"ecosystem\":\"npm\",\"name\":\"example\"},\"versions\":[\"1.0.0\"]}]}", MediaType.APPLICATION_JSON));
    }

    private OsvClient.OsvQuery query() { return new OsvClient.OsvQuery("npm", "example", "1.0.0"); }
}
