package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.client.GitHubAdvisoryClient;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class GitHubBulkCoverageTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void withdrawnObservationsSurviveCompleteAndPartialBulkCollection(boolean partial) throws Exception {
        var withdrawn = new GitHubAdvisoryClient.GitHubAdvisory("GHSA-fixture", null, "fixture", null, null, null,
                null, java.util.Set.of(), "2026-02-01T00:00:00Z", "2026-01-01T00:00:00Z");
        var constructor = GitHubAdvisoryClient.IncompleteLookupException.class.getDeclaredConstructor(List.class, List.class);
        constructor.setAccessible(true);
        var failure = constructor.newInstance(List.of(), List.of(withdrawn));
        try (var clients = mockConstruction(GitHubAdvisoryClient.class, (client, context) -> {
            when(client.canLookup("npm")).thenReturn(true);
            if (partial) when(client.lookupByPackage("npm", "example", "1.0.0")).thenThrow(failure);
            else when(client.lookupByPackage("npm", "example", "1.0.0"))
                    .thenReturn(new GitHubAdvisoryClient.AdvisoryLookup(List.of(), List.of(withdrawn)));
        })) {
            var result = new GitHubAdvisorySource(new ObjectMapper()).fetch(List.of(new WantedComponent("npm", "example", "1.0.0")), "fixture", null);
            assertThat(result.unresolvedKeys().contains("NPM|example|1.0.0")).isEqualTo(partial);
            assertThat(result.vulnsByComponentKey().get("NPM|example|1.0.0")).singleElement().satisfies(row -> {
                assertThat(row.githubUpdatedAt()).isEqualTo(withdrawn.updatedAt());
                assertThat(row.githubWithdrawnAt()).isEqualTo(withdrawn.withdrawnAt());
                assertThat(row.fixVersion()).isNull();
            });
        }
    }
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fixConflictEvidenceSurvivesSnapshotConversion(boolean conflict) throws Exception {
        var candidates = conflict ? java.util.Set.of("2.0.0", "3.0.0") : java.util.Set.<String>of();
        var advisory = new GitHubAdvisoryClient.GitHubAdvisory("GHSA-fixture", "CVE-2026-1000", "fixture", null,
                null, null, "2.0.0", candidates, "2026-02-01T00:00:00Z", null);
        try (var clients = mockConstruction(GitHubAdvisoryClient.class, (client, context) -> {
            when(client.canLookup("npm")).thenReturn(true);
            when(client.lookupByPackage("npm", "example", "1.0.0")).thenReturn(new GitHubAdvisoryClient.AdvisoryLookup(List.of(advisory), List.of()));
        })) {
            var mapper = new ObjectMapper();
            var result = new GitHubAdvisorySource(mapper).fetch(List.of(new WantedComponent("npm", "example", "1.0.0")), "fixture-token", null);
            var finding = result.vulnsByComponentKey().get("NPM|example|1.0.0").getFirst();
            assertThat(finding.fixVersionConflictCandidates()).isEqualTo(candidates);
            assertThat(finding.fixVersion()).isEqualTo(conflict ? null : "2.0.0");
            assertThat(mapper.valueToTree(finding).path("githubUpdatedAt").asText()).isEqualTo("2026-02-01T00:00:00Z");
            var restored = mapper.readValue(mapper.writeValueAsBytes(finding), com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln.class);
            assertThat(restored).isEqualTo(finding);
            assertThat(result.unresolvedKeys()).isEmpty();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unavailableLookupRetainsUnresolvedKey(boolean fails) {
        try (var clients = mockConstruction(GitHubAdvisoryClient.class, (client, context) -> {
            when(client.canLookup("npm")).thenReturn(fails);
            if (fails) when(client.lookupByPackage("npm", "example", "1.0.0")).thenThrow(new IllegalStateException("unavailable"));
        })) {
            var result = new GitHubAdvisorySource(new ObjectMapper()).fetch(List.of(new WantedComponent("npm", "example", "1.0.0")), null, null);
            assertThat(result.unresolvedKeys()).containsExactly("NPM|example|1.0.0");
            assertThat(result.vulnsByComponentKey()).isEmpty();
            if (!fails) verify(clients.constructed().getFirst(), never()).lookupByPackage(anyString(), anyString(), anyString());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void emptySuccessAndPartialFindingsAreRetained(boolean partial) throws Exception {
        var advisory = new GitHubAdvisoryClient.GitHubAdvisory("GHSA-fixture", "CVE-2026-1000", "fixture", null, null, null, "2.0.0");
        var constructor = GitHubAdvisoryClient.IncompleteLookupException.class.getDeclaredConstructor(List.class);
        constructor.setAccessible(true);
        var incomplete = constructor.newInstance(List.of(advisory));
        try (var clients = mockConstruction(GitHubAdvisoryClient.class, (client, context) -> {
            when(client.canLookup("npm")).thenReturn(true);
            if (partial) when(client.lookupByPackage("npm", "example", "1.0.0")).thenThrow(incomplete);
            else when(client.lookupByPackage("npm", "example", "1.0.0")).thenReturn(new GitHubAdvisoryClient.AdvisoryLookup(List.of(), List.of()));
        })) {
            var result = new GitHubAdvisorySource(new ObjectMapper()).fetch(List.of(new WantedComponent("npm", "example", "1.0.0")), "fixture-token", null);
            assertThat(result.vulnsByComponentKey()).containsKey("NPM|example|1.0.0");
            assertThat(result.unresolvedKeys().contains("NPM|example|1.0.0")).isEqualTo(partial);
            var findings = result.vulnsByComponentKey().get("NPM|example|1.0.0");
            if (partial) {
                assertThat(findings).hasSize(1);
                assertThat(findings.getFirst().osvId()).isEqualTo("GHSA-fixture");
                assertThat(findings.getFirst().fixVersion()).isNull();
            } else assertThat(findings).isEmpty();
        }
    }
}
