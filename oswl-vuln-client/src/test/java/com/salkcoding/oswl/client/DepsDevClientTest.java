package com.salkcoding.oswl.client;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.FAST)
@DisplayName("DepsDevClient 단위 테스트")
class DepsDevClientTest {

    private final DepsDevClient client = new DepsDevClient();

    // ── getVersionsBatch: empty input (no HTTP) ───────────────────────────

    @Test
    @DisplayName("getVersionsBatch: 빈 목록은 빈 결과를 반환한다")
    void getVersionsBatch_emptyList_returnsEmptyResult() {
        List<DepsDevClient.VersionInfo> result = client.getVersionsBatch(List.of());

        assertThat(result).isEmpty();
    }

    // ── getAdvisoriesBatch: empty input (no HTTP) ─────────────────────────

    @Test
    @DisplayName("getAdvisoriesBatch: 빈 목록은 빈 결과를 반환한다")
    void getAdvisoriesBatch_emptyList_returnsEmptyResult() {
        List<DepsDevClient.AdvisoryInfo> result = client.getAdvisoriesBatch(List.of());

        assertThat(result).isEmpty();
    }

    // ── ComponentKey record ───────────────────────────────────────────────

    @Test
    @DisplayName("ComponentKey: 레코드 필드를 올바르게 저장한다")
    void componentKey_storesFields() {
        DepsDevClient.ComponentKey key = new DepsDevClient.ComponentKey(
                "Maven", "org.springframework:spring-web", "5.3.0");

        assertThat(key.ecosystem()).isEqualTo("Maven");
        assertThat(key.name()).isEqualTo("org.springframework:spring-web");
        assertThat(key.version()).isEqualTo("5.3.0");
    }

    // ── VersionInfo record ────────────────────────────────────────────────

    @Test
    @DisplayName("VersionInfo: 레코드 필드를 올바르게 저장한다")
    void versionInfo_storesFields() {
        DepsDevClient.VersionInfo info = new DepsDevClient.VersionInfo(
                List.of("Apache-2.0"), List.of("GHSA-1234"), true, null, "5.3.2", true);

        assertThat(info.licenses()).containsExactly("Apache-2.0");
        assertThat(info.advisoryKeys()).containsExactly("GHSA-1234");
        assertThat(info.isDefault()).isTrue();
        assertThat(info.deprecated()).isNull();
        assertThat(info.latestVersion()).isEqualTo("5.3.2");
        assertThat(info.resolved()).isTrue();
    }

    @Test
    @DisplayName("VersionInfo.unresolved: resolved=false")
    void versionInfo_unresolved() {
        assertThat(DepsDevClient.unresolved().resolved()).isFalse();
    }

    // ── AdvisoryInfo record ───────────────────────────────────────────────

    @Test
    @DisplayName("AdvisoryInfo: 레코드 필드를 올바르게 저장한다")
    void advisoryInfo_storesFields() {
        DepsDevClient.AdvisoryInfo info = new DepsDevClient.AdvisoryInfo(
                "GHSA-abcd", "Remote code execution", List.of("CVE-2023-1234"), 9.8, "CVSS:3.1/AV:N");

        assertThat(info.ghsaId()).isEqualTo("GHSA-abcd");
        assertThat(info.title()).isEqualTo("Remote code execution");
        assertThat(info.aliases()).containsExactly("CVE-2023-1234");
        assertThat(info.cvss3Score()).isEqualTo(9.8);
        assertThat(info.cvss3Vector()).isEqualTo("CVSS:3.1/AV:N");
    }

    @Test
    @DisplayName("AdvisoryInfo: snake_case cvss3_vector 필드도 파싱한다")
    void advisoryInfo_parsesSnakeCaseVector() throws Exception {
        var method = DepsDevClient.class.getDeclaredMethod("extractCvss3Vector", Map.class);
        method.setAccessible(true);
        String vector = (String) method.invoke(null, Map.of(
                "cvss3_score", 7.5,
                "cvss3_vector", "CVSS:3.1/AV:L/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:H"));
        assertThat(vector).isEqualTo("CVSS:3.1/AV:L/AC:H/PR:N/UI:N/S:U/C:H/I:H/A:H");
    }
}
