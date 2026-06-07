package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.LicensePolicyEntry;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.license.SpdxLicenseRegistry;
import com.salkcoding.oswl.repository.LicensePolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("LicensePolicyService 단위 테스트")
class LicensePolicyServiceTest {

    @Mock
    LicensePolicyRepository licensePolicyRepository;

    @Mock
    SpdxLicenseRegistry spdxLicenseRegistry;

    @InjectMocks
    LicensePolicyService licensePolicyService;

    @BeforeEach
    void seedCache() {
        when(spdxLicenseRegistry.displayName(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(inv -> inv.getArgument(0));
        when(licensePolicyRepository.findAll()).thenReturn(List.of(
                entry("MIT",        LicenseStatus.PERMITTED),
                entry("APACHE-2.0", LicenseStatus.PERMITTED),
                entry("LGPL-2.1",   LicenseStatus.CAUTION),
                entry("GPL-3.0",      LicenseStatus.RESTRICTED),
                entry("GPL-3.0-only", LicenseStatus.RESTRICTED),
                entry("AGPL-3.0",     LicenseStatus.RESTRICTED)
        ));
        licensePolicyService.refreshCache();
    }

    // ── null / blank ──────────────────────────────────────────────────────

    @Test
    @DisplayName("null 입력이면 UNKNOWN을 반환한다")
    void classify_returnsUnknown_forNull() {
        assertThat(licensePolicyService.classify(null)).isEqualTo(LicenseStatus.UNKNOWN);
    }

    @Test
    @DisplayName("빈 문자열이면 UNKNOWN을 반환한다")
    void classify_returnsUnknown_forBlank() {
        assertThat(licensePolicyService.classify("   ")).isEqualTo(LicenseStatus.UNKNOWN);
    }

    @Test
    @DisplayName("등록되지 않은 SPDX ID는 UNKNOWN을 반환한다")
    void classify_returnsUnknown_forUnregisteredId() {
        assertThat(licensePolicyService.classify("CUSTOM-1.0")).isEqualTo(LicenseStatus.UNKNOWN);
    }

    // ── 직접 조회 ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("등록된 PERMITTED 라이선스는 PERMITTED를 반환한다")
    void classify_returnsPermitted_forMit() {
        assertThat(licensePolicyService.classify("MIT")).isEqualTo(LicenseStatus.PERMITTED);
    }

    @Test
    @DisplayName("소문자 입력도 대소문자 무관하게 조회된다")
    void classify_isCaseInsensitive() {
        assertThat(licensePolicyService.classify("mit")).isEqualTo(LicenseStatus.PERMITTED);
    }

    @Test
    @DisplayName("RESTRICTED 라이선스를 올바르게 반환한다")
    void classify_returnsRestricted_forGpl() {
        assertThat(licensePolicyService.classify("GPL-3.0")).isEqualTo(LicenseStatus.RESTRICTED);
    }

    @Test
    @DisplayName("GPL-3.0-only SPDX suffix도 RESTRICTED로 분류한다")
    void classify_returnsRestricted_forGplOnlySuffix() {
        assertThat(licensePolicyService.classify("GPL-3.0-only")).isEqualTo(LicenseStatus.RESTRICTED);
    }

    @Test
    @DisplayName("CAUTION 라이선스를 올바르게 반환한다")
    void classify_returnsCaution_forLgpl() {
        assertThat(licensePolicyService.classify("LGPL-2.1")).isEqualTo(LicenseStatus.CAUTION);
    }

    // ── OR 표현식 ─────────────────────────────────────────────────────────

    @Nested
    @DisplayName("OR 표현식")
    class OrExpression {

        @Test
        @DisplayName("OR 표현식은 가장 관대한(최소 제한적) 상태를 반환한다")
        void classify_returnsLeastRestrictive_forOrExpr() {
            // MIT(PERMITTED) OR GPL-3.0(RESTRICTED) → PERMITTED
            assertThat(licensePolicyService.classify("MIT OR GPL-3.0"))
                    .isEqualTo(LicenseStatus.PERMITTED);
        }

        @Test
        @DisplayName("OR 표현식에 두 RESTRICTED가 있으면 RESTRICTED를 반환한다")
        void classify_returnsRestricted_whenBothRestrictedInOr() {
            assertThat(licensePolicyService.classify("GPL-3.0 OR AGPL-3.0"))
                    .isEqualTo(LicenseStatus.RESTRICTED);
        }
    }

    // ── AND 표현식 ────────────────────────────────────────────────────────

    @Nested
    @DisplayName("AND 표현식")
    class AndExpression {

        @Test
        @DisplayName("AND 표현식은 가장 제한적인 상태를 반환한다")
        void classify_returnsMostRestrictive_forAndExpr() {
            // MIT(PERMITTED) AND GPL-3.0(RESTRICTED) → RESTRICTED
            assertThat(licensePolicyService.classify("MIT AND GPL-3.0"))
                    .isEqualTo(LicenseStatus.RESTRICTED);
        }

        @Test
        @DisplayName("AND 표현식에 UNKNOWN이 있으면 UNKNOWN을 반환한다")
        void classify_returnsUnknown_whenUnknownInAndExpr() {
            assertThat(licensePolicyService.classify("MIT AND UNKNOWN-LIC"))
                    .isEqualTo(LicenseStatus.UNKNOWN);
        }
    }

    // ── WITH 표현식 ───────────────────────────────────────────────────────

    @Test
    @DisplayName("WITH 수식자는 기본 식별자로 조회한다")
    void classify_stripsWithModifier() {
        assertThat(licensePolicyService.classify("GPL-3.0 WITH Classpath-exception-2.0"))
                .isEqualTo(LicenseStatus.RESTRICTED);
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private static LicensePolicyEntry entry(String spdxId, LicenseStatus status) {
        return LicensePolicyEntry.builder().spdxId(spdxId).status(status).build();
    }

    @Test
    @DisplayName("findEntries: 페이지 단위로 SPDX 항목을 반환한다")
    void findEntries_returnsPagedResults() {
        List<LicensePolicyEntry> pageItems = List.of(
                entry("0BSD", LicenseStatus.PERMITTED),
                entry("MIT", LicenseStatus.PERMITTED)
        );
        Page<LicensePolicyEntry> page = new PageImpl<>(pageItems, Pageable.ofSize(50), 697);
        when(licensePolicyRepository.findAll(any(Pageable.class))).thenReturn(page);

        var result = licensePolicyService.findEntries(null, 0, 50);

        assertThat(result.getItems()).hasSize(2);
        assertThat(result.getTotal()).isEqualTo(697);
        assertThat(result.isHasMore()).isTrue();
    }

    // ── updateEntry ───────────────────────────────────────────────────────

    @Test
    @DisplayName("updateEntry: 이미 존재하는 항목의 상태를 업데이트한다")
    void updateEntry_existingEntry_updatesStatus() {
        LicensePolicyEntry existing = entry("MIT", LicenseStatus.PERMITTED);
        when(licensePolicyRepository.findBySpdxId("MIT")).thenReturn(java.util.Optional.of(existing));
        when(licensePolicyRepository.save(existing)).thenReturn(existing);

        licensePolicyService.updateEntry("MIT", LicenseStatus.CAUTION);

        assertThat(existing.getStatus()).isEqualTo(LicenseStatus.CAUTION);
        org.mockito.Mockito.verify(licensePolicyRepository).save(existing);
    }

    @Test
    @DisplayName("updateEntry: 항목이 없으면 새로 생성하여 저장한다")
    void updateEntry_newEntry_createsAndSaves() {
        when(licensePolicyRepository.findBySpdxId("CUSTOM-LIC")).thenReturn(java.util.Optional.empty());
        when(licensePolicyRepository.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> inv.getArgument(0));

        licensePolicyService.updateEntry("CUSTOM-LIC", LicenseStatus.RESTRICTED);

        org.mockito.Mockito.verify(licensePolicyRepository).save(
                org.mockito.ArgumentMatchers.argThat(e ->
                        "CUSTOM-LIC".equals(e.getSpdxId()) && e.getStatus() == LicenseStatus.RESTRICTED));
    }
}
