package com.salkcoding.oswl.license;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.FAST)
@DisplayName("SpdxLicenseClassifier")
class SpdxLicenseClassifierTest {

    @Test
    @DisplayName("OSI permissive licenses are PERMITTED")
    void mit_isPermitted() {
        assertThat(SpdxLicenseClassifier.classify("MIT", true)).isEqualTo(LicenseStatus.PERMITTED);
    }

    @Test
    @DisplayName("GPL family is RESTRICTED")
    void gplOnly_isRestricted() {
        assertThat(SpdxLicenseClassifier.classify("GPL-3.0-only", false)).isEqualTo(LicenseStatus.RESTRICTED);
    }

    @Test
    @DisplayName("LGPL is CAUTION, not RESTRICTED")
    void lgpl_isCaution() {
        assertThat(SpdxLicenseClassifier.classify("LGPL-2.1-only", false)).isEqualTo(LicenseStatus.CAUTION);
    }

    @Test
    @DisplayName("AGPL is RESTRICTED")
    void agpl_isRestricted() {
        assertThat(SpdxLicenseClassifier.classify("AGPL-3.0-only", false)).isEqualTo(LicenseStatus.RESTRICTED);
    }

    @Test
    @DisplayName("Non-OSI obscure license defaults to CAUTION")
    void obscure_defaultsToCaution() {
        assertThat(SpdxLicenseClassifier.classify("Abstyles", false)).isEqualTo(LicenseStatus.CAUTION);
    }

    @Test
    @DisplayName("CC-BY-NC is RESTRICTED")
    void ccByNc_isRestricted() {
        assertThat(SpdxLicenseClassifier.classify("CC-BY-NC-4.0", false)).isEqualTo(LicenseStatus.RESTRICTED);
    }
}
