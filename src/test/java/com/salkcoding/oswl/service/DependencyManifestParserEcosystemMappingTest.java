package com.salkcoding.oswl.service;
import com.salkcoding.oswl.service.ingest.DependencyManifestParserService;
import com.salkcoding.oswl.service.vulnerability.VulnerabilityEnrichmentService;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the silent-false-negative ecosystem mapping bug (ROADMAP A0).
 *
 * <p>The manifest parser emits upper-case internal ecosystem tags; every tag it can emit
 * must be queryable — either through {@link VulnerabilityEnrichmentService}'s OSV mapping,
 * or (for C/C++ ecosystems, which OSV has no usable data for) through the NVD CPE path.
 * A tag in neither set would cause OSV to receive the raw internal tag, match nothing, and
 * report a green "no vulnerabilities" result for every component of that ecosystem.
 *
 * <p>SUBMODULE is the one deliberate exception, carved out below: a pinned git submodule's
 * "version" is its commit SHA, and feeding a SHA into NVD's exact-match CPE lookup was verified
 * (live NVD query, 2026-08-13) to return unrelated CVEs rather than an empty result — a false
 * positive, worse than the "no vulnerabilities" false negative this test otherwise guards
 * against. So SUBMODULE is intentionally left unqueried (shown as not-analyzed) instead of
 * being wired to either path.
 */
class DependencyManifestParserEcosystemMappingTest {

    @Test
    @DisplayName("파서가 낼 수 있는 모든 ecosystem 태그가 OSV 매핑 또는 CPE 경로에 포함된다 (SUBMODULE 제외)")
    void parserEcosystemsAreSubsetOfOsvMapping() {
        Set<String> queryable = new HashSet<>(VulnerabilityEnrichmentService.osvMappedEcosystems());
        queryable.addAll(VulnerabilityEnrichmentService.cpeEcosystems());

        Set<String> emitted = new HashSet<>(DependencyManifestParserService.EMITTED_ECOSYSTEMS);
        emitted.remove("SUBMODULE");

        assertThat(emitted)
                .isNotEmpty()
                .isSubsetOf(queryable);
    }

    @Test
    @DisplayName("SUBMODULE은 CPE 경로에서 의도적으로 제외된다 (SHA를 버전으로 오인해 잘못된 CVE를 붙이는 것을 방지)")
    void submoduleIsDeliberatelyExcludedFromCpeMatching() {
        assertThat(VulnerabilityEnrichmentService.cpeEcosystems()).doesNotContain("SUBMODULE");
        assertThat(DependencyManifestParserService.EMITTED_ECOSYSTEMS).contains("SUBMODULE");
    }

    @Test
    @DisplayName("Composer와 Conan은 OSV의 공식 ecosystem 이름으로 매핑된다")
    void composerAndConanMapToOsvNames() {
        assertThat(VulnerabilityEnrichmentService.toOsvEcosystem("COMPOSER")).isEqualTo("Packagist");
        assertThat(VulnerabilityEnrichmentService.toOsvEcosystem("CONAN")).isEqualTo("ConanCenter");
    }
}
