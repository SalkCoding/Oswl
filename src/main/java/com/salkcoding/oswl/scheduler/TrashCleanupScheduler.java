package com.salkcoding.oswl.scheduler;

import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
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

    /**
     * {@code @SchedulerLock} (roadmap S1) is a no-op unless {@code oswl.scheduler-lock.enabled=true}
     * (see {@link SchedulerLockConfig}) — a single instance behaves exactly as before S1.
     */
    @Scheduled(cron = "0 0 2 * * *")
    @SchedulerLock(name = "TrashCleanupScheduler_purgeExpiredTrash",
            lockAtLeastFor = "PT1M", lockAtMostFor = "PT10M")
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
