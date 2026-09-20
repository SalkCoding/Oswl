package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.client.NvdClient;
import com.salkcoding.oswl.domain.enums.MatchConfidence;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class NvdBulkCoverageTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void applicabilitySurvivesConversionAndJson(boolean partial) throws Exception {
        String evidence = "{\"id\":\"CVE-2026-1000\",\"configurations\":[{\"operator\":\"AND\"}]}";
        var finding = new NvdClient.NvdCve("CVE-2026-1000", "fixture", RiskLevel.HIGH,
                7.5, null, MatchConfidence.HIGH, evidence);
        var constructor = NvdClient.IncompleteLookupException.class.getDeclaredConstructor(List.class);
        constructor.setAccessible(true);
        var incomplete = constructor.newInstance(List.of(finding));
        try (var clients = mockConstruction(NvdClient.class, (client, context) -> {
            if (partial) when(client.findByCpeName(anyString(), any())).thenThrow(incomplete);
            else when(client.findByCpeName(anyString(), any())).thenReturn(List.of(finding));
        })) {
            var mapper = new ObjectMapper();
            var result = new NvdSource(mapper).fetch(List.of(new WantedComponent("CONAN", "openssl", "1.0")), null);
            assertThat(result.vulnsByComponentKey()).containsKey("CONAN|openssl|1.0");
            var saved = result.vulnsByComponentKey().get("CONAN|openssl|1.0").getFirst();
            assertThat(result.unresolvedKeys().contains("CONAN|openssl|1.0")).isEqualTo(partial);
            assertThat(saved.nvdApplicability()).isEqualTo(evidence);
            assertThat(saved.matchConfidence()).isEqualTo("HIGH");
            assertThat(saved.fixVersion()).isNull();
            assertThat(mapper.readValue(mapper.writeValueAsBytes(saved), SnapshotVuln.class)).isEqualTo(saved);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void failedCandidateDoesNotDiscardOtherCandidates(boolean firstFails) {
        var finding = new NvdClient.NvdCve("CVE-2026-1001", "fixture", null, null, null, MatchConfidence.LOW);
        try (var clients = mockConstruction(NvdClient.class, (client, context) -> {
            when(client.findByCpeName(anyString(), any())).thenAnswer(invocation -> {
                boolean first = invocation.<String>getArgument(0).contains(":fixture:fixture:");
                if (first == firstFails) throw new IllegalStateException("unavailable");
                return List.of(finding);
            });
        })) {
            var result = new NvdSource(new ObjectMapper()).fetch(List.of(new WantedComponent("SYSTEM", "libfixture", "1.0")), null);
            assertThat(result.vulnsByComponentKey()).containsKey("SYSTEM|libfixture|1.0");
            assertThat(result.vulnsByComponentKey().get("SYSTEM|libfixture|1.0")).extracting(SnapshotVuln::cveId)
                    .containsExactly("CVE-2026-1001");
            assertThat(result.unresolvedKeys()).containsExactly("SYSTEM|libfixture|1.0");
            verify(clients.constructed().getFirst(), times(2)).findByCpeName(anyString(), any());
        }
    }
    @ParameterizedTest
    @ValueSource(strings = {"empty", "failed", "unsupported"})
    void emptySuccessIsDifferentFromUnavailableLookup(String mode) {
        try (var clients = mockConstruction(NvdClient.class, (client, context) -> {
            if (mode.equals("failed")) when(client.findByCpeName(anyString(), any())).thenThrow(new IllegalStateException("unavailable"));
            else when(client.findByCpeName(anyString(), any())).thenReturn(List.of());
        })) {
            String ecosystem = mode.equals("unsupported") ? "NPM" : "CONAN";
            String key = ecosystem + "|openssl|1.0";
            var result = new NvdSource(new ObjectMapper()).fetch(List.of(new WantedComponent(ecosystem, "openssl", "1.0")), null);
            assertThat(result.unresolvedKeys().contains(key)).isEqualTo(!mode.equals("empty"));
            if (mode.equals("unsupported")) {
                assertThat(result.vulnsByComponentKey()).isEmpty();
                verify(clients.constructed().getFirst(), never()).findByCpeName(anyString(), any());
            } else assertThat(result.vulnsByComponentKey()).containsEntry(key, List.of());
        }
    }

}
