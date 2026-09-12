package com.salkcoding.oswl.client;

import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.vulnerability.sources.GitHubAdvisorySource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class GitHubNugetQueryIdentityTest {
    @ParameterizedTest
    @CsvSource({"System.Text.Json,8.*,false", "System.Text.Json,'[8.0.3,9.0)',false",
            "System.Text.Json,[8.0.3],false", "System.Text.Json,$(Version),false",
            "System.Text.Json,1..0,false", "System.Text.Json,2147483648.0,false",
            "System.Text.Json,1.0.0-alpha.01,false", "Contoso/Library,1.0.0,false",
            "Contoso..Library,1.0.0,false", "ƛ,1.0.0,false",
            "System.Text.Json,8.0.3,true", "system.text.json,08.0.03.0,true",
            "SYSTEM.TEXT.JSON,1.0.0-alpha.1+build,true", "Σ,1.0,true"})
    void emptyResultsCannotResolveAnUnconfirmedNugetIdentity(String name, String version, boolean valid) {
        for (boolean offline : List.of(false, true)) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            var requests = new AtomicInteger();
            server.expect(ExpectedCount.between(0, 1), anything()).andRespond(request -> {
                requests.incrementAndGet();
                return withSuccess("""
                        {"data":{"securityVulnerabilities":{"pageInfo":{"hasNextPage":false},"nodes":[]}}}
                        """, MediaType.APPLICATION_JSON).createResponse(request);
            });
            var snapshot = mock(AirgappedSnapshotService.class);
            var client = new GitHubAdvisoryClient(snapshot, offline, "fixture", "https://api.github.com",
                    Duration.ofSeconds(1), Duration.ofSeconds(1));
            ReflectionTestUtils.setField(client, "restClient", builder.build());
            var source = new GitHubAdvisorySource(client);
            var result = offline
                    ? source.lookupSnapshot("NuGet", name, version, new com.salkcoding.oswl.dto.snapshot.SnapshotLookup<>(List.of(), true))
                    : source.lookup("NuGet", name, version, List.of());
            assertThat(result.lookupFailed()).as("offline=%s", offline).isEqualTo(!valid);
            assertThat(result.findings()).isEmpty();
            assertThat(requests.get()).isEqualTo(valid && !offline ? 1 : 0);
            server.verify();
        }
    }
}
