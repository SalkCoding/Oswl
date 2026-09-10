package com.salkcoding.oswl.service.scan;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.*;
import com.salkcoding.oswl.domain.entity.vulnerability.*;
import com.salkcoding.oswl.domain.enums.*;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.scan.*;
import com.salkcoding.oswl.repository.vulnerability.LibraryRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ScanVersionDiffProjectionTest {
    @Autowired ProjectRepository projects;
    @Autowired ScanResultRepository scans;
    @Autowired ScanComponentRepository components;
    @Autowired LibraryRepository libraries;
    @Autowired ScanVersionDiffAnalyzer analyzer;
    @Autowired EntityManager em;
    @Autowired EntityManagerFactory emf;

    @Test void projectionPreservesVersionsDuplicateHandlingAndThreatPriorityWithoutHydratingCves() {
        var project = projects.save(Project.builder().name("Diff-" + UUID.randomUUID()).build());
        var from = scans.save(ScanResult.builder().project(project).version("1").status(ScanStatus.COMPLETED).build());
        var to = scans.save(ScanResult.builder().project(project).version("2").status(ScanStatus.COMPLETED).build());
        String name = "lib-" + UUID.randomUUID();
        var oldLib = libraries.save(Library.builder().name(name).version("1").ecosystem("NPM").build());
        var threat = Library.builder().name(name).version("2").ecosystem("NPM").build();
        threat.getCves().add(Cve.builder().library(threat).severity(RiskLevel.LOW).build());
        threat.getCves().add(Cve.builder().library(threat).severity(RiskLevel.CRITICAL).build());
        threat = libraries.save(threat);
        components.saveAll(List.of(
                ScanComponent.builder().scanResult(from).library(oldLib).build(),
                ScanComponent.builder().scanResult(from).library(oldLib).build(),
                ScanComponent.builder().scanResult(to).library(threat).build(),
                ScanComponent.builder().scanResult(to).library(threat).build()));
        em.flush(); em.clear();
        var stats = emf.unwrap(SessionFactory.class).getStatistics();
        stats.setStatisticsEnabled(true); stats.clear();
        var result = analyzer.compare(from.getId(), to.getId());
        assertThat(result.rows()).hasSize(1);
        assertThat(result.newThreats()).isEqualTo(1);
        assertThat(result.rows().getFirst().getFromVersion()).isEqualTo("1");
        assertThat(result.rows().getFirst().getToVersion()).isEqualTo("2");
        assertThat(result.rows().getFirst().getToRiskLevel()).isEqualTo("CRITICAL");
        assertThat(stats.getEntityLoadCount()).isZero();
    }
}
