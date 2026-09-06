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
                    "{\"results\":[{\"vulns\":[{\"id\":\"GHSA-fixture\"}]},{\"vulns\":[{\"id\":\"GHSA-fixture\"}]}]}", MediaType.APPLICATION_JSON));
            server.expect(requestTo("https://api.osv.dev/v1/vulns/GHSA-fixture")).andRespond(success ? withSuccess(
                    "{\"id\":\"GHSA-fixture\",\"aliases\":[\"CVE-2026-0001\"],\"severity\":[{\"type\":\"CVSS_V3\",\"score\":\"CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H\"}]}", MediaType.APPLICATION_JSON) : withServerError());
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
