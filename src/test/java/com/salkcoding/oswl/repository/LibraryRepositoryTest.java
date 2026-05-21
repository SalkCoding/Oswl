package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.Library;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@DisplayName("LibraryRepository 통합 테스트")
class LibraryRepositoryTest {

    @Autowired LibraryRepository libraryRepository;

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

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private static Library lib(String name, String version, String ecosystem) {
        return Library.builder()
                .name(name)
                .version(version)
                .ecosystem(ecosystem)
                .build();
    }
}
