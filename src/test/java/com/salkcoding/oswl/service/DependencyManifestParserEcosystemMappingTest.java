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
 * or (for C/C++ ecosystems added in ROADMAP A8, which OSV has no usable data for) through
 * the NVD CPE path. A tag in neither set would cause OSV to receive the raw internal tag,
 * match nothing, and report a green "no vulnerabilities" result for every component of
 * that ecosystem.
 */
class DependencyManifestParserEcosystemMappingTest {

    @Test
    @DisplayName("파서가 낼 수 있는 모든 ecosystem 태그가 OSV 매핑 또는 CPE 경로에 포함된다")
    void parserEcosystemsAreSubsetOfOsvMapping() {
        Set<String> queryable = new HashSet<>(VulnerabilityEnrichmentService.osvMappedEcosystems());
        queryable.addAll(VulnerabilityEnrichmentService.cpeEcosystems());

        assertThat(DependencyManifestParserService.EMITTED_ECOSYSTEMS)
                .isNotEmpty()
                .isSubsetOf(queryable);
    }

    @Test
    @DisplayName("Composer와 Conan은 OSV의 공식 ecosystem 이름으로 매핑된다")
    void composerAndConanMapToOsvNames() {
        assertThat(VulnerabilityEnrichmentService.toOsvEcosystem("COMPOSER")).isEqualTo("Packagist");
        assertThat(VulnerabilityEnrichmentService.toOsvEcosystem("CONAN")).isEqualTo("ConanCenter");
    }
}
