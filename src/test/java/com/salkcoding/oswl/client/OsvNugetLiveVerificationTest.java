package com.salkcoding.oswl.client;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

/** Opt-in read-only verification against OSV; set OSWL_VERIFY_OSV_NUGET=true to run. */
@EnabledIfEnvironmentVariable(named = "OSWL_VERIFY_OSV_NUGET", matches = "true")
class OsvNugetLiveVerificationTest {
    @Test
    void microsoftAdvisoryBoundaryMatchesTheLiveClient() {
        var versions = List.of("7.0.0", "8.0.3", "8.0.4");
        var results = new OsvClient().queryBatch(versions.stream()
                .map(version -> new OsvClient.OsvQuery("NuGet", "System.Text.Json", version)).toList());
        assertThat(results).hasSize(versions.size());
        for (int i = 0; i < versions.size(); i++) {
            var result = results.get(i);
            assertThat(result.resolved()).as("complete live lookup for %s", versions.get(i)).isTrue();
            var target = result.vulns().stream().filter(v -> "GHSA-hh2w-p6rv-4g7w".equals(v.osvId())).toList();
            assertThat(target).hasSize(i < 2 ? 1 : 0);
            if (i < 2) {
                assertThat(target.getFirst().fixVersion()).isEqualTo("8.0.4");
                assertThat(target.getFirst().cveId()).isEqualTo("CVE-2024-30105");
            }
            // The fixed boundary for one advisory says nothing about other advisories.
            System.out.println("System.Text.Json " + versions.get(i) + ": " + result.vulns().stream()
                    .map(v -> v.osvId() + " -> " + v.fixVersion()).sorted().toList());
        }
    }
}
