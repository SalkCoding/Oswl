package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.client.GitHubAdvisoryClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitHubCliBuildTest {
    @TempDir Path directory;

    @ParameterizedTest
    @ValueSource(strings = {"finding", "empty", "failed"})
    void cliBuildWritesSelectedGithubDataAndUncertainty(String state) throws Exception {
        Path wanted = directory.resolve("wanted.jsonl");
        Files.writeString(wanted, "{\"ecosystem\":\"npm\",\"name\":\"fixture\",\"version\":\"1.0.0\"}\n");
        Path output = directory.resolve("bundle.zip");
        var parsed = VdbBuildOptions.parse(List.of("--sources", "github-advisory", "--wanted", wanted.toString(), "--out", output.toString()));
        var opts = new VdbBuildOptions(parsed.out(), parsed.wantedList(), parsed.sources(), null, directory.resolve("cache"),
                null, null, "synthetic-test-token", null, null, "unreviewed");
        try (var clients = mockConstruction(GitHubAdvisoryClient.class, (client, context) -> {
            when(client.canLookup("npm")).thenReturn(true);
            if (state.equals("failed")) when(client.lookupByPackage("npm", "fixture", "1.0.0")).thenThrow(new IllegalStateException("synthetic failure"));
            else when(client.lookupByPackage("npm", "fixture", "1.0.0")).thenReturn(new GitHubAdvisoryClient.AdvisoryLookup(
                    state.equals("empty") ? List.of() : List.of(new GitHubAdvisoryClient.GitHubAdvisory("GHSA-fixture", null, "fixture", null, null, null, "2.0.0")), List.of()));
        })) {
            Integer code = ReflectionTestUtils.invokeMethod(new VdbBuilderCli(), "build", opts);
            assertThat(code).isEqualTo(state.equals("failed") ? 2 : 0);
            var previous = PreviousBundleReader.read(output, new ObjectMapper());
            var rows = previous.linesByFileAndKey().get("github-advisory.jsonl");
            if (state.equals("failed")) {
                assertThat(rows).isEmpty();
                assertThat(previous.linesByFileAndKey().get("unresolved.jsonl")).containsKey("NPM|fixture|1.0.0");
            } else {
                var findings = new ObjectMapper().readTree(rows.get("NPM|fixture|1.0.0")).path("vulns");
                assertThat(findings.size()).isEqualTo(state.equals("empty") ? 0 : 1);
                if (!state.equals("empty")) assertThat(findings.get(0).path("fixVersion").asText()).isEqualTo("2.0.0");
            }
        }
    }
}
