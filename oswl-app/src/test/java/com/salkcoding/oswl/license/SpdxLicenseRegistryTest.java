package com.salkcoding.oswl.license;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.FAST)
@DisplayName("SpdxLicenseRegistry")
class SpdxLicenseRegistryTest {

    @Test
    @DisplayName("bundled SPDX list loads hundreds of active licenses")
    void loadsSpdxList() {
        SpdxLicenseRegistry registry = new SpdxLicenseRegistry();
        registry.load();
        assertThat(registry.allActive()).hasSizeGreaterThan(600);
        assertThat(registry.displayName("MIT")).isEqualTo("MIT License");
        assertThat(registry.displayName("GPL-3.0-only")).contains("GNU General Public License");
        assertThat(registry.referenceUrl("MIT")).isEqualTo("https://spdx.org/licenses/MIT.html");
        assertThat(registry.referenceUrl("CUSTOM-LIC-1.0")).isEqualTo("https://spdx.org/licenses/CUSTOM-LIC-1.0.html");
    }
}
