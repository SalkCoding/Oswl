package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.dto.ProjectSummaryDto;
import com.salkcoding.oswl.repository.ProjectRepository;
import com.salkcoding.oswl.repository.ScanResultRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.format.DateTimeFormatter;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ProjectService {

    private final ProjectRepository projectRepository;
    private final ScanResultRepository scanResultRepository;

    @Transactional(readOnly = true)
    public List<ProjectSummaryDto> findAll() {
        return projectRepository.findAll().stream()
                .map(this::toSummary)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public Project getById(Long id) {
        return projectRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + id));
    }

    @Transactional
    public Project create(String name) {
        Project project = Project.builder().name(name).build();
        return projectRepository.save(project);
    }

    @Transactional
    public Project createFromGitHub(String owner, String repo, String branch) {
        String name = owner + "/" + repo;
        Project project = Project.builder().name(name).build();
        project.markGithubImport(owner, repo, branch);
        return projectRepository.save(project);
    }

    @Transactional
    public void delete(Long id) {
        projectRepository.deleteById(id);
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private static final DateTimeFormatter IMPORT_FMT = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm");

    private ProjectSummaryDto toSummary(Project project) {
        String importedAt = project.getImportedAt() != null
                ? project.getImportedAt().format(IMPORT_FMT)
                : null;
        // Extract aggregate values from the latest completed scan
        return scanResultRepository
                .findFirstByProjectIdAndStatusOrderByScannedAtDesc(
                        project.getId(),
                        com.salkcoding.oswl.domain.enums.ScanStatus.COMPLETED)
                .map(scan -> {
                    int[] sec = aggregateSecurity(scan);
                    int[] lic = aggregateLicense(scan);
                    String lastScanned = scan.getScannedAt() != null
                            ? scan.getScannedAt().toLocalDate().toString().replace("-", ".")
                            : "-";
                    return ProjectSummaryDto.builder()
                            .id(project.getId())
                            .name(project.getName())
                            .version(scan.getVersion())
                            .lastScanned(lastScanned)
                            .securityCritical(sec[0]).securityHigh(sec[1])
                            .securityMedium(sec[2]).securityLow(sec[3])
                            .licenseCritical(lic[0]).licenseHigh(lic[1])
                            .licenseMedium(lic[2]).licenseLow(lic[3])
                            .githubRepo(project.getGithubRepo())
                            .importedAt(importedAt)
                            .build();
                })
                .orElseGet(() -> ProjectSummaryDto.builder()
                        .id(project.getId())
                        .name(project.getName())
                        .version("-")
                        .lastScanned("-")
                        .githubRepo(project.getGithubRepo())
                        .importedAt(importedAt)
                        .build());
    }

    private int[] aggregateSecurity(com.salkcoding.oswl.domain.entity.ScanResult scan) {
        int critical = 0, high = 0, medium = 0, low = 0;
        for (var comp : scan.getComponents()) {
            for (var cve : comp.getCves()) {
                switch (cve.getSeverity()) {
                    case CRITICAL -> critical++;
                    case HIGH     -> high++;
                    case MEDIUM   -> medium++;
                    case LOW      -> low++;
                    default       -> {}
                }
            }
        }
        return new int[]{critical, high, medium, low};
    }

    private int[] aggregateLicense(com.salkcoding.oswl.domain.entity.ScanResult scan) {
        int critical = 0, high = 0, medium = 0, low = 0;
        for (var comp : scan.getComponents()) {
            switch (comp.getLicenseStatus()) {
                case VIOLATION -> critical++;
                case WARN      -> high++;
                default        -> low++;
            }
        }
        return new int[]{critical, high, medium, low};
    }
}
