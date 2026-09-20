package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.entity.vulnerability.Cve;
import com.salkcoding.oswl.domain.enums.CveSource;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import com.salkcoding.oswl.repository.vulnerability.CveRepository;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import org.hibernate.SessionFactory;
import java.util.List;
import java.util.Set;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@DisplayName("LibraryRepository 통합 테스트")
class LibraryRepositoryTest {

    @Autowired LibraryRepository libraryRepository;
    @Autowired CveRepository cveRepository;
    @Autowired ProjectRepository projectRepository;
    @Autowired ScanResultRepository scanResultRepository;
    @Autowired ScanComponentRepository scanComponentRepository;
    @Autowired EntityManager entityManager;

    @Test
    @DisplayName("name+version+ecosystem 조합으로 라이브러리를 찾을 수 있다")
    void findByNameAndVersionAndEcosystem_returnsMatchingLibrary() {
        libraryRepository.save(lib("spring-core-test", "6.0.0", "MAVEN"));

        Optional<Library> found =
                libraryRepository.findByNameAndVersionAndEcosystem("spring-core-test", "6.0.0", "MAVEN");

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("spring-core-test");
    }

    @Test
    @DisplayName("ecosystem이 다르면 찾을 수 없다")
    void findByNameAndVersionAndEcosystem_returnsEmpty_whenEcosystemDiffers() {
        libraryRepository.save(lib("lodash-test", "4.17.21", "NPM"));

        Optional<Library> found =
                libraryRepository.findByNameAndVersionAndEcosystem("lodash-test", "4.17.21", "PYPI");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("version이 다르면 찾을 수 없다")
    void findByNameAndVersionAndEcosystem_returnsEmpty_whenVersionDiffers() {
        libraryRepository.save(lib("jackson-test", "2.14.0", "MAVEN"));

        Optional<Library> found =
                libraryRepository.findByNameAndVersionAndEcosystem("jackson-test", "2.15.0", "MAVEN");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("저장된 라이브러리를 ID로 조회할 수 있다")
    void findById_returnsSavedLibrary() {
        Library saved = libraryRepository.save(lib("guava-test", "32.0.0", "MAVEN"));

        Optional<Library> found = libraryRepository.findById(saved.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getLicenseStatus()).isEqualTo(LicenseStatus.UNKNOWN);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"libraryIds", "scan", "scans", "components", "detail"})
    @DisplayName("CVE with multiple sources is loaded once by every CVE fetch query")
    void cveWithMultipleSourcesIsNotDuplicatedByCveFetchQueries(String query) {
        Project project = projectRepository.save(Project.builder().name("library-repository-cve-sources").build());
        ScanResult scan = scanResultRepository.save(ScanResult.builder().project(project).build());
        Library library = libraryRepository.save(lib("multi-source-cve", "1.0.0", "CONAN"));
        cveRepository.save(Cve.builder().library(library).cveId("CVE-2026-7654")
                .sources(Set.of(CveSource.NVD, CveSource.CPE)).build());
        ScanComponent component = scanComponentRepository.save(ScanComponent.builder().scanResult(scan).library(library).build());
        libraryRepository.flush();
        entityManager.clear();

        var found = switch (query) {
            case "libraryIds" -> libraryRepository.findByIdInWithCves(List.of(library.getId()));
            case "scan" -> libraryRepository.findByScanResultIdWithCves(scan.getId());
            case "scans" -> libraryRepository.findByScanResultIdInWithCves(List.of(scan.getId()));
            case "components" -> scanComponentRepository.findByScanResultId(scan.getId()).stream().map(ScanComponent::getLibrary).toList();
            case "detail" -> scanComponentRepository.findByIdAndProjectIdWithCves(component.getId(), project.getId()).stream()
                    .map(ScanComponent::getLibrary).toList();
            default -> throw new IllegalArgumentException(query);
        };
        assertLoadedOnce(found);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"library", "components", "detail"})
    @DisplayName("bulk CVE source loading stays batched")
    void bulkCveSourceLoadingStaysBatched(String query) {
        Library library = libraryRepository.save(lib("bulk-multi-source-cve", "1.0.0", "CONAN"));
        var project = projectRepository.save(Project.builder().name("bulk-source-query").build());
        var scan = scanResultRepository.save(ScanResult.builder().project(project).build());
        var component = scanComponentRepository.save(ScanComponent.builder().scanResult(scan).library(library).build());
        for (int i = 0; i < 60; i++) {
            cveRepository.save(Cve.builder().library(library)
                    .cveId("CVE-2026-" + String.format("%04d", i))
                    .fixVersionConflictCandidates(Set.of("2.0.0", "3.0.0"))
                    .sources(Set.of(CveSource.NVD, CveSource.CPE)).build());
        }
        libraryRepository.flush();
        entityManager.clear();

        var statistics = entityManager.getEntityManagerFactory().unwrap(SessionFactory.class).getStatistics();
        boolean previouslyEnabled = statistics.isStatisticsEnabled();
        statistics.setStatisticsEnabled(true);
        try {
            statistics.clear();
            List<Library> found = switch (query) {
                case "library" -> libraryRepository.findByIdInWithCves(List.of(library.getId()));
                case "components" -> scanComponentRepository.findByScanResultId(scan.getId()).stream().map(ScanComponent::getLibrary).toList();
                case "detail" -> scanComponentRepository.findByIdAndProjectIdWithCves(component.getId(), project.getId())
                        .stream().map(ScanComponent::getLibrary).toList();
                default -> throw new IllegalArgumentException(query);
            };
            assertThat(found).singleElement().satisfies(result -> {
                assertThat(result.getCves()).hasSize(60)
                        .extracting(Cve::getCveId).doesNotHaveDuplicates();
                assertThat(result.getCves()).allSatisfy(cve -> {
                    assertThat(cve.getSources()).containsExactlyInAnyOrder(CveSource.NVD, CveSource.CPE);
                    assertThat(cve.getFixVersionConflictCandidates()).containsExactlyInAnyOrder("2.0.0", "3.0.0");
                });
            });
            assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(5);
        } finally {
            statistics.setStatisticsEnabled(previouslyEnabled);
        }
    }

    private static void assertLoadedOnce(List<Library> libraries) {
        assertThat(libraries).singleElement().satisfies(library -> {
            assertThat(library.getCves()).singleElement().satisfies(cve ->
                    assertThat(cve.getSources()).containsExactlyInAnyOrder(CveSource.NVD, CveSource.CPE));
        });
    }

    @Test
    void componentMetadataQueryPreservesOccurrencesWithoutHydratingCurrentFindings() {
        var project = projectRepository.save(Project.builder().name("snapshot-row-metadata").build());
        var scan = scanResultRepository.save(ScanResult.builder().project(project).build());
        var library = libraryRepository.save(lib("snapshot-row-library", "1.0", "NPM"));
        cveRepository.save(Cve.builder().library(library).cveId("CVE-2026-98765")
                .sources(Set.of(CveSource.NVD, CveSource.CPE)).build());
        var runtime = scanComponentRepository.save(ScanComponent.builder().scanResult(scan).library(library).scope("runtime").build());
        var test = scanComponentRepository.save(ScanComponent.builder().scanResult(scan).library(library).scope("test").build());
        entityManager.flush();
        entityManager.clear();
        var found = scanComponentRepository.findByScanResultIdWithLibrary(scan.getId());
        assertThat(found).extracting(ScanComponent::getId).containsExactlyInAnyOrder(runtime.getId(), test.getId());
        assertThat(found).allSatisfy(component -> {
            assertThat(component.getLibrary().getName()).isEqualTo("snapshot-row-library");
            assertThat(org.hibernate.Hibernate.isInitialized(component.getLibrary().getCves())).isFalse();
        });
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private static Library lib(String name, String version, String ecosystem) {
        return Library.builder()
                .name(name)
                .version(version)
                .ecosystem(ecosystem)
                .build();
    }
}
