package com.salkcoding.oswl.client;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.FAST)
@DisplayName("KevCatalogService")
class KevCatalogServiceTest {

    @Test
    @DisplayName("isListed returns false before refresh and for invalid ids")
    void notListedByDefault() {
        KevCatalogService service = new KevCatalogService();
        assertThat(service.isListed(null)).isFalse();
        assertThat(service.isListed("GHSA-xxx")).isFalse();
        assertThat(service.isListed("CVE-1999-0001")).isFalse();
    }
}
