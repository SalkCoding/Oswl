package com.salkcoding.oswl.client;

import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.anything;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OsvNugetQueryIdentityTest {
    @org.junit.jupiter.api.Test
    void rejectingADeclarationKeepsNeighboringBatchResultsAligned() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(anything()).andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.content().json(
                "{\"queries\":[{\"version\":\"8.0.3\",\"package\":{\"name\":\"System.Text.Json\",\"ecosystem\":\"NuGet\"}},"
                        + "{\"version\":\"8.0.4\",\"package\":{\"name\":\"System.Text.Json\",\"ecosystem\":\"NuGet\"}}]}"))
                .andRespond(withSuccess("{\"results\":[{},{}]}", MediaType.APPLICATION_JSON));
        var client = new OsvClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        var queries = List.of(new OsvClient.OsvQuery("NuGet", "System.Text.Json", "8.0.3"),
                new OsvClient.OsvQuery("NuGet", "System.Text.Json", "8.*"),
                new OsvClient.OsvQuery("NuGet", "System.Text.Json", "8.0.4"));
        assertThat(client.queryBatch(queries)).extracting(OsvClient.OsvResult::resolved).containsExactly(true, false, true);
        var snapshot = mock(AirgappedSnapshotService.class);
        when(snapshot.findOsvVulns(anyCollection())).thenReturn(java.util.Map.of(
                "NUGET|System.Text.Json|8.0.3", List.of(), "NUGET|System.Text.Json|8.0.4", List.of()));
        assertThat(new OsvClient(snapshot, true).queryBatch(queries)).extracting(OsvClient.OsvResult::resolved)
                .containsExactly(true, false, true);
        verify(snapshot).findOsvVulns(java.util.Set.of("NUGET|System.Text.Json|8.0.3", "NUGET|System.Text.Json|8.0.4"));
        server.verify();
    }

    @ParameterizedTest
    @CsvSource({
            "System.Text.Json,8.*,false", "System.Text.Json,'[8.0.3,9.0)',false",
            "System.Text.Json,[8.0.3],false", "System.Text.Json,$(Version),false",
            "System.Text.Json,1..0,false", "System.Text.Json,2147483648.0,false",
            "System.Text.Json,1.0.0-alpha.01,false", "Contoso/Library,1.0.0,false",
            "Contoso..Library,1.0.0,false", "ƛ,1.0.0,false",
            "System.Text.Json,8.0.3,true", "system.text.json,08.0.03.0,true",
            "SYSTEM.TEXT.JSON,1.0.0-alpha.1+build,true", "Σ,1.0,true"
    })
    void emptyOnlineAndStoredResponsesOnlyResolveConcreteSupportedIdentities(String name, String version, boolean valid) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var requests = new AtomicInteger();
        server.expect(ExpectedCount.between(0, 1), anything()).andRespond(request -> {
            requests.incrementAndGet();
            return withSuccess("{\"results\":[{}]}", MediaType.APPLICATION_JSON).createResponse(request);
        });
        var online = new OsvClient();
        ReflectionTestUtils.setField(online, "restClient", builder.build());
        var snapshot = mock(AirgappedSnapshotService.class);
        when(snapshot.findOsvVulns(anyCollection())).thenAnswer(call -> {
            var found = new LinkedHashMap<String, List<AirgappedSnapshotService.SnapshotVuln>>();
            for (String key : call.<java.util.Collection<String>>getArgument(0)) found.put(key, List.of());
            return found;
        });
        var query = new OsvClient.OsvQuery("NuGet", name, version);
        var live = online.queryBatch(List.of(query)).getFirst();
        var offline = new OsvClient(snapshot, true).queryBatch(List.of(query)).getFirst();
        assertThat(live.resolved()).as("online").isEqualTo(valid);
        assertThat(offline.resolved()).as("offline").isEqualTo(valid);
        assertThat(live.vulns()).isEmpty();
        assertThat(offline.vulns()).isEmpty();
        assertThat(requests.get()).isEqualTo(valid ? 1 : 0);
        server.verify();
    }
}
