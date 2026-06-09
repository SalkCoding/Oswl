package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Backfills {@code project_members} for projects created before project ACL was introduced.
 */
@Slf4j
@Component
@Order(40)
@RequiredArgsConstructor
public class ProjectMemberBootstrapRunner implements ApplicationListener<ApplicationReadyEvent> {

    private final ProjectRepository projectRepository;
    private final ProjectAccessService projectAccessService;

    @Override
    @Transactional
    public void onApplicationEvent(ApplicationReadyEvent event) {
        int processed = 0;
        for (Project project : projectRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc()) {
            if (project.getCreatedByUserId() != null) {
                projectAccessService.ensureCreatorMemberIfAbsent(project);
                processed++;
            }
        }
        if (processed > 0) {
            log.info("[ProjectACL] Creator membership bootstrap processed {} project(s)", processed);
        }
    }
}
