package com.salkcoding.oswl.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.service.vulnerability.sources.GitHubAdvisorySource;
import com.salkcoding.oswl.vdb.OsvFixVersionSelector;
import com.salkcoding.oswl.vdb.OsvRangeEvaluator;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class NuGetAdvisoryParityTest {
    @ParameterizedTest
    @CsvSource({"1.0.0-alpha10,true,false", "1.0.0-alpha2,false,false", "1.0.0-alpha2+build,false,false", "1.*,false,true"})
    void providersAgreeOnNativeOrderingAndFixedBoundary(String installed, boolean affected, boolean unknown) throws Exception {
        var json = new ObjectMapper();
        var advisory = json.readTree("""
                {"affected":[{"package":{"ecosystem":"NuGet","name":"fixture"},"ranges":[
                  {"type":"ECOSYSTEM","events":[{"introduced":"0"},{"fixed":"1.0.0-alpha2"}]}]}]}
                """);
        var ranges = advisory.path("affected").get(0).path("ranges");
        assertThat(OsvRangeEvaluator.evaluate("NUGET", installed, null, ranges)).isEqualTo(unknown
                ? OsvRangeEvaluator.Result.UNKNOWN : affected ? OsvRangeEvaluator.Result.AFFECTED : OsvRangeEvaluator.Result.NOT_AFFECTED);
        assertThat(OsvFixVersionSelector.select(advisory, "NuGet", "fixture", installed).version())
                .isEqualTo(affected ? "1.0.0-alpha2" : null);
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new GitHubAdvisoryClient(null, false, "fixture", "https://api.github.com",
                Duration.ofSeconds(1), Duration.ofSeconds(1));
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        if (!unknown) server.expect(anything()).andRespond(withSuccess("""
                {"data":{"securityVulnerabilities":{"pageInfo":{"hasNextPage":false,"endCursor":"end"},"nodes":[
                  {"package":{"name":"fixture","ecosystem":"NUGET"},"vulnerableVersionRange":"< 1.0.0-alpha2",
                   "firstPatchedVersion":{"identifier":"1.0.0-alpha2"},
                   "advisory":{"identifiers":[{"type":"GHSA","value":"GHSA-fixture"}]}}]}}}
                """, MediaType.APPLICATION_JSON));
        var actual = new GitHubAdvisorySource(client).lookup("NuGet", "fixture", installed, List.of());
        assertThat(actual.lookupFailed()).isEqualTo(unknown);
        assertThat(actual.findings()).hasSize(affected ? 1 : 0);
        if (affected) assertThat(actual.findings().getFirst().fixVersion()).isEqualTo("1.0.0-alpha2");
        server.verify();
    }
}
