package com.salkcoding.oswl.client;

import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class KevLookupStatusTest {
    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void absentMembershipRequiresFreshCoverageAndPositiveEvidenceSurvives(boolean stale) {
        var snapshots = mock(AirgappedSnapshotService.class);
        when(snapshots.loadKevCveIds()).thenReturn(Set.of("CVE-2026-0001"));
        when(snapshots.isSourceStaleOrUndated("kev")).thenReturn(stale);
        var client = new KevCatalogService(snapshots, true);
        assertThat(client.listingStatus("CVE-2026-0002")).isNull();
        client.refresh();
        assertThat(client.listingStatus(" cve-2026-0001 ")).isTrue();
        assertThat(client.listingStatus("CVE-2026-0002")).isEqualTo(stale ? null : Boolean.FALSE);
        var cve = Cve.builder().build();
        cve.setThreatIntel(null, client.listingStatus("CVE-2026-0002"));
        assertThat(cve.getKevListed()).isEqualTo(stale ? null : Boolean.FALSE);
    }

    @Test void unloadedAndExpiredOnlineCatalogsCannotConfirmAbsence() {
        var client = new KevCatalogService();
        assertThat(client.listingStatus("CVE-2026-0002")).isNull();
        ReflectionTestUtils.setField(client, "catalog", new KevCatalogService.CatalogState(
                Set.of("CVE-2026-0001"), Instant.now().minus(Duration.ofDays(2))));
        assertThat(client.listingStatus("CVE-2026-0001")).isTrue();
        assertThat(client.listingStatus("CVE-2026-0002")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"vulnerabilities\":[null]}",
            "{\"vulnerabilities\":[{\"cveID\":\"CVE-2026-0002\"},{\"cveID\":42}]}"})
    void malformedRefreshRetainsPriorPositiveEvidenceAndInvalidatesAbsence(String body) {
        var builder = org.springframework.web.client.RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var client = new KevCatalogService();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        ReflectionTestUtils.setField(client, "catalog", new KevCatalogService.CatalogState(
                Set.of("CVE-2026-0001"), Instant.now()));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        body, org.springframework.http.MediaType.APPLICATION_JSON));
        client.refresh();
        assertThat(client.listingStatus("CVE-2026-0001")).isTrue();
        assertThat(client.listingStatus("CVE-2026-0002")).isNull();
        server.verify();
    }
}
