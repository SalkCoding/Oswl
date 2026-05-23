package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.LicensePolicyEntry;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
@DisplayName("LicensePolicyRepository 통합 테스트")
class LicensePolicyRepositoryTest {

    @Autowired LicensePolicyRepository licensePolicyRepository;

    @Test
    @DisplayName("spdxId로 라이선스 정책을 조회할 수 있다")
    void findBySpdxId_returnsMatchingEntry() {
        // LicensePolicyService.init()이 MIT 등 기본값을 시딩하므로 MIT은 이미 존재함
        Optional<LicensePolicyEntry> found = licensePolicyRepository.findBySpdxId("MIT");

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(LicenseStatus.PERMITTED);
    }

    @Test
    @DisplayName("존재하지 않는 spdxId는 빈 Optional을 반환한다")
    void findBySpdxId_returnsEmpty_whenNotFound() {
        Optional<LicensePolicyEntry> found = licensePolicyRepository.findBySpdxId("NO-SUCH-LICENSE");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("RESTRICTED 라이선스 목록을 조회할 수 있다")
    void findByStatus_returnsRestrictedEntries() {
        // 기본 시딩: GPL-3.0, AGPL-3.0 등이 RESTRICTED로 등록됨
        List<LicensePolicyEntry> restricted = licensePolicyRepository.findByStatus(LicenseStatus.RESTRICTED);

        assertThat(restricted).isNotEmpty();
        assertThat(restricted).allMatch(e -> e.getStatus() == LicenseStatus.RESTRICTED);
    }

    @Test
    @DisplayName("PERMITTED 라이선스 목록을 조회할 수 있다")
    void findByStatus_returnsPermittedEntries() {
        List<LicensePolicyEntry> permitted = licensePolicyRepository.findByStatus(LicenseStatus.PERMITTED);

        assertThat(permitted).isNotEmpty();
        assertThat(permitted).anyMatch(e -> "MIT".equals(e.getSpdxId()));
    }
}
