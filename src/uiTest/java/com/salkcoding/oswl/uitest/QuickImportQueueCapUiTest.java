package com.salkcoding.oswl.uitest;

import com.salkcoding.oswl.exception.QuickImportQueueFullException;
import com.salkcoding.oswl.service.ingest.QuickImportService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ROADMAP D5 DoD point 2: "429 rejection happens exactly at the cap." Separated from
 * {@link QuickImportLoadUiTest} because it needs {@code max-concurrent} pinned to 0 — with any
 * real dispatch, the submitted jobs would fail (and leave Phase.QUEUED) in under a millisecond,
 * making the queued-count boundary this test checks a race instead of a deterministic assertion.
 * Pinning max-concurrent to 0 means nothing ever gets dispatched, so every submitted job stays in
 * Phase.QUEUED for the test's lifetime — exactly the state {@code countUserQueuedJobs()} counts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("uitest")
@TestPropertySource(properties = "oswl.quick-import.max-concurrent=0")
class QuickImportQueueCapUiTest {

    @Autowired
    private QuickImportService quickImportService;

    @Value("${oswl.quick-import.max-queued-per-user:3}")
    private int maxQueuedPerUser;

    @Test
    @DisplayName("Per-user queue cap: the (max+1)th queued import is rejected, not silently accepted")
    void perUserQueueCapRejectsExactlyAtLimit() {
        long userId = System.nanoTime();
        for (int i = 0; i < maxQueuedPerUser; i++) {
            String jobId = quickImportService.startImport(
                    "https://d5-load-test.invalid/oswl-loadtest/repo-" + i, null, userId);
            assertThat(jobId).isNotNull();
        }
        assertThatThrownBy(() -> quickImportService.startImport(
                "https://d5-load-test.invalid/oswl-loadtest/repo-999", null, userId))
                .isInstanceOf(QuickImportQueueFullException.class);
    }
}
