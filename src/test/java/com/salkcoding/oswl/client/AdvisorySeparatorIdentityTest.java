package com.salkcoding.oswl.client;

import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.vulnerability.sources.GitHubAdvisorySource;
import com.salkcoding.oswl.dto.snapshot.SnapshotLookup;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.web.client.RestClient;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AdvisorySeparatorIdentityTest {
    @ParameterizedTest
    @CsvSource({"vendor/pkg|other,1.0.0,false", "vendor/pkg,1.0|other,false", "vendor/pkg,1.0.0,true"})
    void emptyResultsCannotResolveAmbiguousCoordinates(String name, String version, boolean valid) {
        for (boolean github : List.of(false, true)) {
            var builder = RestClient.builder();
            var server = MockRestServiceServer.bindTo(builder).build();
            var calls = new AtomicInteger();
            server.expect(ExpectedCount.between(0, 1), anything()).andRespond(request -> {
                calls.incrementAndGet();
                String body = github ? "{\"data\":{\"securityVulnerabilities\":{\"pageInfo\":{\"hasNextPage\":false},\"nodes\":[]}}}"
                        : "{\"results\":[{}]}";
                return withSuccess(body, MediaType.APPLICATION_JSON).createResponse(request);
            });
            var snapshot = mock(AirgappedSnapshotService.class);
            if (github) {
                var client = new GitHubAdvisoryClient(snapshot, false, "fixture", "https://api.github.com", Duration.ofSeconds(1), Duration.ofSeconds(1));
                ReflectionTestUtils.setField(client, "restClient", builder.build());
                var source = new GitHubAdvisorySource(client);
                assertThat(source.lookup("COMPOSER", name, version, List.of()).lookupFailed()).isEqualTo(!valid);
                assertThat(source.lookupSnapshot("COMPOSER", name, version, new SnapshotLookup<>(List.of(), true)).lookupFailed()).isEqualTo(!valid);
            } else {
                when(snapshot.readOsvSnapshot(anyCollection())).thenCallRealMethod();
                when(snapshot.findOsvVulns(anyCollection())).thenReturn(java.util.Map.of("COMPOSER|vendor/pkg|1.0.0", List.of()));
                var client = new OsvClient();
                ReflectionTestUtils.setField(client, "restClient", builder.build());
                var queries = List.of(new OsvClient.OsvQuery("Packagist", name, version));
                assertThat(client.queryBatch(queries).getFirst().resolved()).isEqualTo(valid);
                assertThat(new OsvClient(snapshot, true).queryBatch(queries).getFirst().resolved()).isEqualTo(valid);
            }
            assertThat(calls.get()).isEqualTo(valid ? 1 : 0);
            server.verify();
        }
    }
}
