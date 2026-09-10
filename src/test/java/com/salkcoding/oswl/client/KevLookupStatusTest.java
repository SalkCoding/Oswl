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
    @ValueSource(booleans = {false, true})
    void sameReleaseCannotSilentlyChangeMembership(boolean conflict) {
        var builder = org.springframework.web.client.RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var client = new KevCatalogService();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        String released = Instant.now().minusSeconds(60).toString();
        for (String ids : new String[]{"[1,2]", conflict ? "[2,3]" : "[2,1]", "[1,2]"}) {
            String rows = ids.equals("[1,2]") ? "[{\"cveID\":\"CVE-2026-0001\"},{\"cveID\":\"CVE-2026-0002\"}]"
                    : "[{\"cveID\":\"CVE-2026-0002\"},{\"cveID\":\"CVE-2026-000" + (conflict ? "3" : "1") + "\"}]";
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                    "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                            "{\"count\":2,\"catalogVersion\":\"v1\",\"dateReleased\":\"" + released
                                    + "\",\"vulnerabilities\":" + rows + "}", org.springframework.http.MediaType.APPLICATION_JSON));
        }
        client.refresh();
        client.refresh();
        client.refresh();
        assertThat(client.listingStatus("CVE-2026-0001")).isTrue();
        assertThat(client.listingStatus("CVE-2026-0002")).isTrue();
        assertThat(client.listingStatus("CVE-2026-0003")).isEqualTo(conflict ? null : Boolean.FALSE);
        server.verify();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,false", "7,false", "8,true", "40,true"})
    void downloadingAnOldCatalogDoesNotRefreshItsSourceDate(int age, boolean stale) {
        var builder = org.springframework.web.client.RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var client = new KevCatalogService();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        String released = Instant.now().minus(Duration.ofDays(age)).toString();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        "{\"count\":1,\"catalogVersion\":\"v1\",\"dateReleased\":\"" + released
                                + "\",\"vulnerabilities\":[{\"cveID\":\"CVE-2026-0001\"}]}",
                        org.springframework.http.MediaType.APPLICATION_JSON));
        client.refresh();
        assertThat(client.listingStatus("CVE-2026-0001")).isTrue();
        assertThat(client.listingStatus("CVE-2026-0002")).isEqualTo(stale ? null : Boolean.FALSE);
        server.verify();
    }

    @ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(value = {
            "1;CURRENT;v1;true", "2;2026-01-01T00:00:00Z;v1;false",
            "0;2026-01-01T00:00:00Z;v1;false", "-1;2026-01-01T00:00:00Z;v1;false",
            "null;2026-01-01T00:00:00Z;v1;false", "1.5;2026-01-01T00:00:00Z;v1;false",
            "1;bad-date;v1;false", "1;2099-01-01T00:00:00Z;v1;false", "1;2026-01-01T00:00:00Z;;false"
    }, delimiter = ';', emptyValue = "")
    void catalogMetadataMustSupportReplacingPriorEvidence(String count, String released, String version, boolean valid) {
        if ("CURRENT".equals(released)) released = Instant.now().toString();
        var builder = org.springframework.web.client.RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var client = new KevCatalogService();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        ReflectionTestUtils.setField(client, "catalog", new KevCatalogService.CatalogState(Set.of("CVE-2026-0001"), Instant.now()));
        String body = "{\"count\":" + count + ",\"dateReleased\":\"" + released + "\",\"catalogVersion\":\""
                + (version == null ? "" : version) + "\",\"vulnerabilities\":[{\"cveID\":\"CVE-2026-0002\"}]}";
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(body,
                        org.springframework.http.MediaType.APPLICATION_JSON));
        client.refresh();
        assertThat(client.listingStatus("CVE-2026-0001")).isEqualTo(!valid);
        assertThat(client.listingStatus("CVE-2026-0002")).isEqualTo(valid ? Boolean.TRUE : null);
        server.verify();
    }

    @Test void olderReleaseCannotReplacePreviouslyAcceptedMembership() {
        var builder = org.springframework.web.client.RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var client = new KevCatalogService();
        ReflectionTestUtils.setField(client, "restClient", builder.build());
        for (int age : new int[]{1, 2}) {
            String released = Instant.now().minus(Duration.ofDays(age)).toString();
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                    "https://www.cisa.gov/sites/default/files/feeds/known_exploited_vulnerabilities.json"))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                            "{\"count\":1,\"catalogVersion\":\"v1\",\"dateReleased\":\"" + released
                                    + "\",\"vulnerabilities\":[{\"cveID\":\"CVE-2026-000" + age + "\"}]}",
                            org.springframework.http.MediaType.APPLICATION_JSON));
        }
        client.refresh();
        assertThat(client.listingStatus("CVE-2026-0002")).isFalse();
        client.refresh();
        assertThat(client.listingStatus("CVE-2026-0001")).isTrue();
        assertThat(client.listingStatus("CVE-2026-0002")).isNull();
        server.verify();
    }

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
