package com.salkcoding.oswl.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Guarantees the organization/team skeleton exists on ddl-auto deployments (local H2).
 * Flyway-managed deployments get the same rows from the schema migration; both paths are
 * idempotent and converge on: one organization, one "Default" team, no team-less projects.
 */
@Slf4j
@Component
@Order(41)
@RequiredArgsConstructor
public class TeamBootstrapRunner implements ApplicationListener<ApplicationReadyEvent> {

    private final TeamService teamService;

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        teamService.ensureDefaults();
    }
}
