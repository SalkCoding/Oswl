package com.salkcoding.oswl.repository;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.domain.entity.LicensePolicyEntry;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.INTEGRATION)
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
}
