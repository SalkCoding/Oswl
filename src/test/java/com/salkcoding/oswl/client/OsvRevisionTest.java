package com.salkcoding.oswl.client;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OsvRevisionTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("https://api.osv.dev");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final OsvClient client = new OsvClient();

    OsvRevisionTest() { ReflectionTestUtils.setField(client, "restClient", builder.build()); }

    @ParameterizedTest
    @CsvSource({"2026-01-01T00:00:00Z,2026-01-01T00:00:00Z,true",
            "2026-01-01T00:00:00Z,2026-01-01T00:00:00.000Z,true",
            "2026-01-01T00:00:00.123456Z,2026-01-01T00:00:00.123456789Z,true",
            "2026-01-01T00:00:00.123457Z,2026-01-01T00:00:00.123456789Z,false",
            "2026-01-01T00:00:00.123Z,2026-01-01T00:00:00.123456789Z,false",
            "2026-01-01T00:00:00.123456001Z,2026-01-01T00:00:00.123456789Z,false",
            "2026-01-01T00:00:00Z,2026-01-02T00:00:00Z,false",
            "2026-01-02T00:00:00Z,2026-01-01T00:00:00Z,false",
            "missing,missing,false", "missing,2026-01-01T00:00:00Z,false",
            "2026-01-01T00:00:00Z,missing,false", "invalid,invalid,false",
            "null,null,false", "true,true,false", "9999-01-01T00:00:00Z,9999-01-01T00:00:00Z,false"})
    void onlyMatchingValidRevisionsSupplyDetailedFindings(String queried, String hydrated, boolean complete) {
        batch("{\"results\":[{\"vulns\":[" + stub(queried) + "]}]}");
        detail(hydrated, "");
        var result = client.queryBatch(List.of(query())).getFirst();
        assertThat(result.resolved()).isEqualTo(complete);
        assertThat(result.vulns()).singleElement().satisfies(vuln -> {
            assertThat(vuln.osvId()).isEqualTo("OSV-fixture");
            assertThat(vuln.fixVersion()).isEqualTo(complete ? "1.2.4" : null);
            assertThat(vuln.summary()).isEqualTo(complete ? "fixture" : null);
        });
        server.verify();
    }

    @Test void aLaterRevisionConflictCannotKeepOrRestoreAnEarlierFix() {
        batch("{\"results\":[{\"vulns\":[" + stub("2026-01-01T00:00:00Z") + "],\"next_page_token\":\"cursor\"}]}");
        detail("2026-01-01T00:00:00Z", "");
        batch("{\"results\":[{\"vulns\":[" + stub("2026-01-02T00:00:00Z") + "," + stub("2026-01-01T00:00:00Z") + "]}]}");
        var result = client.queryBatch(List.of(query())).getFirst();
        assertThat(result.resolved()).isFalse();
        assertThat(result.vulns()).singleElement().satisfies(vuln -> assertThat(vuln.fixVersion()).isNull());
        server.verify();
    }

    @Test void withdrawalFromADifferentRevisionCannotEraseAQueriedFinding() {
        batch("{\"results\":[{\"vulns\":[" + stub("2026-01-01T00:00:00Z") + "]}]}");
        detail("2026-01-02T00:00:00Z", ",\"withdrawn\":\"2026-01-02T00:00:00Z\"");
        var result = client.queryBatch(List.of(query())).getFirst();
        assertThat(result.resolved()).isFalse();
        assertThat(result.vulns()).hasSize(1);
        server.verify();
    }

    @Test void sharedDetailCacheIsCheckedAgainstEachQueryRevision() {
        batch("{\"results\":[{\"vulns\":[" + stub("2026-01-01T00:00:00Z") + "]},{\"vulns\":["
                + stub("2026-01-02T00:00:00Z") + "]}]}");
        detail("2026-01-01T00:00:00Z", "");
        var results = client.queryBatch(List.of(query(), new OsvClient.OsvQuery("npm", "example", "1.2.2")));
        assertThat(results.get(0).resolved()).isTrue();
        assertThat(results.get(0).vulns().getFirst().fixVersion()).isEqualTo("1.2.4");
        assertThat(results.get(1).resolved()).isFalse();
        assertThat(results.get(1).vulns().getFirst().fixVersion()).isNull();
        server.verify();
    }

    private String stub(String revision) { return "{\"id\":\"OSV-fixture\"" + modified(revision) + "}"; }

    private String modified(String revision) {
        if (revision.equals("missing")) return "";
        return ",\"modified\":" + (revision.equals("null") || revision.equals("true") ? revision : "\"" + revision + "\"");
    }

    private void batch(String body) {
        server.expect(requestTo("https://api.osv.dev/v1/querybatch")).andRespond(withSuccess(body, MediaType.APPLICATION_JSON));
    }

    private void detail(String revision, String extra) {
        server.expect(requestTo("https://api.osv.dev/v1/vulns/OSV-fixture")).andRespond(withSuccess("""
                {"id":"OSV-fixture","summary":"fixture"%s%s,
                "affected":[{"package":{"ecosystem":"npm","name":"example"},
                "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"1.2.4"}]}]}]}
                """.formatted(modified(revision), extra), MediaType.APPLICATION_JSON));
    }

    private OsvClient.OsvQuery query() { return new OsvClient.OsvQuery("npm", "example", "1.2.3"); }
}
