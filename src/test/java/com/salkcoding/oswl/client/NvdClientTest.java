package com.salkcoding.oswl.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("NvdClient 단위 테스트")
class NvdClientTest {

    private final NvdClient client = new NvdClient();

    // ── fetchCve: early-return paths (no HTTP) ────────────────────────────

    @Test
    @DisplayName("fetchCve: cveId가 null이면 null을 반환한다")
    void fetchCve_nullId_returnsNull() {
        NvdClient.NvdCveInfo result = client.fetchCve(null, "any-key");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("fetchCve: CVE-로 시작하지 않는 ID는 null을 반환한다")
    void fetchCve_nonCveId_returnsNull() {
        NvdClient.NvdCveInfo result = client.fetchCve("GHSA-1234-5678-90ab", "any-key");

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("fetchCve: 빈 문자열은 null을 반환한다")
    void fetchCve_emptyId_returnsNull() {
        NvdClient.NvdCveInfo result = client.fetchCve("", "any-key");

        assertThat(result).isNull();
    }

    // ── NvdCveInfo record ─────────────────────────────────────────────────

    @Test
    @DisplayName("NvdCveInfo: 레코드 필드를 올바르게 저장한다")
    void nvdCveInfo_storesFields() {
        NvdClient.NvdCveInfo info = new NvdClient.NvdCveInfo(9.8, "CRITICAL", "CWE-79", "CVSS3");

        assertThat(info.cvssScore()).isEqualTo(9.8);
        assertThat(info.severity()).isEqualTo("CRITICAL");
        assertThat(info.cweId()).isEqualTo("CWE-79");
        assertThat(info.cvss3Vector()).isEqualTo("CVSS3");
    }

    @Test
    @DisplayName("NvdCveInfo: null 필드도 허용된다")
    void nvdCveInfo_allowsNullFields() {
        NvdClient.NvdCveInfo info = new NvdClient.NvdCveInfo(null, null, null, null);

        assertThat(info.cvssScore()).isNull();
        assertThat(info.severity()).isNull();
        assertThat(info.cweId()).isNull();
        assertThat(info.cvss3Vector()).isNull();
    }
}
