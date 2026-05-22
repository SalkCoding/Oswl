package com.salkcoding.oswl.scheduler;

import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Permanently deletes projects that have been in the trash for more than 30 days.
 * Runs every day at 2:00 AM (server local time).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TrashCleanupScheduler {

    private final ProjectRepository projectRepository;

    @Scheduled(cron = "0 0 2 * * *")
    @Transactional
    public void purgeExpiredTrash() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(30);
        List<Project> expired = projectRepository.findAllByDeletedAtBefore(cutoff);
        if (!expired.isEmpty()) {
            projectRepository.deleteAll(expired);
            log.info("[TrashCleanup] Permanently deleted {} expired projects", expired.size());
        }
    }
}
