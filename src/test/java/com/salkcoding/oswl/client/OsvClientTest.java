package com.salkcoding.oswl.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OsvClient 단위 테스트")
class OsvClientTest {

    private final OsvClient client = new OsvClient();

    // ── queryBatch: early-return for empty input (no HTTP) ────────────────

    @Test
    @DisplayName("queryBatch: 빈 쿼리 리스트는 HTTP 호출 없이 빈 결과를 반환한다")
    void queryBatch_emptyList_returnsEmptyResult() {
        List<OsvClient.OsvResult> result = client.queryBatch(Collections.emptyList());

        assertThat(result).isEmpty();
    }

    // ── OsvQuery record ───────────────────────────────────────────────────

    @Test
    @DisplayName("OsvQuery: 레코드 필드를 올바르게 저장한다")
    void osvQuery_storesFields() {
        OsvClient.OsvQuery query = new OsvClient.OsvQuery("Maven", "org.springframework:spring-web", "5.3.0");

        assertThat(query.ecosystem()).isEqualTo("Maven");
        assertThat(query.name()).isEqualTo("org.springframework:spring-web");
        assertThat(query.version()).isEqualTo("5.3.0");
    }

    // ── OsvVuln record ────────────────────────────────────────────────────

    @Test
    @DisplayName("OsvVuln: 레코드 필드를 올바르게 저장한다")
    void osvVuln_storesFields() {
        OsvClient.OsvVuln vuln = new OsvClient.OsvVuln("GHSA-1234", "CVE-2023-1234", "RCE flaw", "5.3.1");

        assertThat(vuln.osvId()).isEqualTo("GHSA-1234");
        assertThat(vuln.cveId()).isEqualTo("CVE-2023-1234");
        assertThat(vuln.summary()).isEqualTo("RCE flaw");
        assertThat(vuln.fixVersion()).isEqualTo("5.3.1");
    }

    // ── OsvResult record ──────────────────────────────────────────────────

    @Test
    @DisplayName("OsvResult: 빈 vuln 리스트를 허용한다")
    void osvResult_emptyVulns() {
        OsvClient.OsvResult result = new OsvClient.OsvResult(List.of());

        assertThat(result.vulns()).isEmpty();
    }

    @Test
    @DisplayName("OsvResult: vuln 리스트를 그대로 보관한다")
    void osvResult_storesVulns() {
        OsvClient.OsvVuln vuln = new OsvClient.OsvVuln("G1", "CVE-X", "desc", null);
        OsvClient.OsvResult result = new OsvClient.OsvResult(List.of(vuln));

        assertThat(result.vulns()).containsExactly(vuln);
    }
}
