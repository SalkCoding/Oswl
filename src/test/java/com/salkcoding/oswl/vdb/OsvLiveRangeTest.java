package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.client.OsvClient;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class OsvLiveRangeTest {
    @ParameterizedTest
    @CsvSource({"1.2.3,true,true,false", "1.2.4,false,true,false", "1.2.4+build.1,false,true,false",
            "1.2.4-rc.1,true,true,false", "1.2,false,false,false", "9.0.0,true,true,false",
            "1.2.3,true,true,true", "1.2.4,false,false,true"})
    void liveHydrationAndBulkSnapshotUseTheSameVersionEvidence(String version, boolean affected, boolean resolved, boolean unknownSibling) {
        String advisory = advisory("""
                [{"package":{"ecosystem":"npm","name":"example"},"versions":["9.0.0"],
                "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"1.2.4"}]}]}]
                """);
        if (unknownSibling) {
            advisory = advisory.replace("\"affected\":[", "\"affected\":[{\"package\":{\"ecosystem\":\"npm\",\"name\":\"example\"}," +
                    "\"ranges\":[{\"type\":\"GIT\",\"events\":[{\"introduced\":\"0\"}]}]},");
        }
        var live = lookup(advisory, version);
        assertThat(live.resolved()).isEqualTo(resolved);
        assertThat(live.vulns()).hasSize(affected ? 1 : 0);

        Map<String, List<AirgappedSnapshotService.SnapshotVuln>> findings = new LinkedHashMap<>();
        Set<String> unknown = new LinkedHashSet<>();
        ReflectionTestUtils.invokeMethod(new OsvBulkSource(new ObjectMapper()), "processVulnEntry",
                advisory.getBytes(StandardCharsets.UTF_8), "NPM", Map.of("example", Set.of(version)), findings, unknown);
        String key = AirgappedSnapshotService.componentKey("npm", "example", version);
        var store = mock(AirgappedSnapshotService.class);
        org.mockito.Mockito.when(store.readOsvSnapshot(org.mockito.ArgumentMatchers.anyCollection())).thenCallRealMethod();
        when(store.findOsvVulns(anyCollection())).thenReturn(Map.of(key, findings.getOrDefault(key, List.of())));
        when(store.findUnresolvedKeys(anyCollection())).thenReturn(unknown);
        var offline = new OsvClient(store, true).queryBatch(List.of(new OsvClient.OsvQuery("npm", "example", version))).getFirst();
        assertThat(offline.resolved()).isEqualTo(live.resolved());
        assertThat(offline.vulns()).extracting(OsvClient.OsvVuln::osvId, OsvClient.OsvVuln::fixVersion)
                .containsExactlyElementsOf(live.vulns().stream().map(v -> org.assertj.core.groups.Tuple.tuple(v.osvId(), v.fixVersion())).toList());
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "[]", "[{}]",
            "[{\"package\":{\"ecosystem\":\"npm\",\"name\":\"other\"},\"versions\":[\"1.2.3\"]}]",
            "[{\"package\":{\"ecosystem\":\"PyPI\",\"name\":\"example\"},\"versions\":[\"1.2.3\"]}]",
            "[{\"package\":{\"ecosystem\":\"npm\",\"name\":\"example\"},\"versions\":false}]",
            "[{\"package\":{\"ecosystem\":\"npm\",\"name\":\"example\"},\"ranges\":[{\"type\":\"GIT\",\"events\":[{\"introduced\":\"0\"}]}]}]"})
    void unconfirmedPackageOrRangeDoesNotCreateAConfirmedFinding(String affected) {
        var result = lookup(advisory(affected), "1.2.3");
        assertThat(result.resolved()).isFalse();
        assertThat(result.vulns()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource(value = {"npm;*;false", "crates.io;latest;false", "Go;master;false", "PyPI;>=1;false",
            "Maven;[1,2);false", "npm;1.0.0;true", "crates.io;1.0.0;true", "Go;v1.0.0;true",
            "PyPI;1.0.post1;true", "Maven;1.0.Final;true"}, delimiter = ';')
    void emptyLookupStillRequiresAConcreteInstalledVersion(String ecosystem, String version, boolean resolved) {
        var builder = RestClient.builder().baseUrl("https://api.osv.dev");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new OsvClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(requestTo("https://api.osv.dev/v1/querybatch"))
                .andRespond(withSuccess("{\"results\":[{}]}", MediaType.APPLICATION_JSON));
        var query = new OsvClient.OsvQuery(ecosystem, "fixture", version);
        var online = client.queryBatch(List.of(query)).getFirst();
        assertThat(online.resolved()).isEqualTo(resolved);
        var store = mock(AirgappedSnapshotService.class);
        when(store.readOsvSnapshot(anyCollection())).thenCallRealMethod();
        String key = AirgappedSnapshotService.componentKey(ecosystem, "fixture", version);
        when(store.findOsvVulns(anyCollection())).thenReturn(Map.of(key, List.of()));
        when(store.findUnresolvedKeys(anyCollection())).thenReturn(Set.of());
        var offline = new OsvClient(store, true).queryBatch(List.of(query)).getFirst();
        assertThat(offline.resolved()).isEqualTo(resolved);
        assertThat(offline.vulns()).isEmpty();
        assertThat(offline.commonFix().version()).isNull();
        var retained = new AirgappedSnapshotService.SnapshotVuln("OSV-fixture", "CVE-2026-123450", "fixture",
                "2.0.0", null, "HIGH", 7.5, null, null);
        when(store.findOsvVulns(anyCollection())).thenReturn(Map.of(key, List.of(retained)));
        var cached = new OsvClient(store, true).queryBatch(List.of(query)).getFirst();
        assertThat(cached.resolved()).isEqualTo(resolved);
        assertThat(cached.vulns()).singleElement().satisfies(v -> {
            assertThat(v.osvId()).isEqualTo("OSV-fixture");
            assertThat(v.fixVersion()).isEqualTo(resolved ? "2.0.0" : null);
        });
        assertThat(retained.fixVersion()).isEqualTo("2.0.0");
        server.verify();
    }

    private String advisory(String affected) {
        return "{\"id\":\"OSV-fixture\",\"modified\":\"2026-01-01T00:00:00Z\",\"affected\":" + affected + "}";
    }

    private OsvClient.OsvResult lookup(String advisory, String version) {
        var builder = RestClient.builder().baseUrl("https://api.osv.dev");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new OsvClient();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        server.expect(requestTo("https://api.osv.dev/v1/querybatch")).andRespond(withSuccess(
                "{\"results\":[{\"vulns\":[{\"id\":\"OSV-fixture\",\"modified\":\"2026-01-01T00:00:00Z\"}]}]}", MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://api.osv.dev/v1/vulns/OSV-fixture")).andRespond(withSuccess(advisory, MediaType.APPLICATION_JSON));
        var result = client.queryBatch(List.of(new OsvClient.OsvQuery("npm", "example", version))).getFirst();
        server.verify();
        return result;
    }
}
