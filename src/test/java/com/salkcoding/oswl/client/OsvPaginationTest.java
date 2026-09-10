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
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.osv.dev");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OsvClient client = new OsvClient();

    OsvPaginationTest() {
        ReflectionTestUtils.setField(client, "restClient", builder.build());
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
                .andRespond(withSuccess("{\"modified\":\"2026-01-01T00:00:00Z\",\"id\":\"" + id + "\"}", MediaType.APPLICATION_JSON));
    }

    private OsvClient.OsvQuery query() { return new OsvClient.OsvQuery("npm", "example", "1.0.0"); }
}
