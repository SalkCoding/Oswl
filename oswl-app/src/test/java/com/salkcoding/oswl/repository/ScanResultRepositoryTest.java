package com.salkcoding.oswl.repository;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.INTEGRATION)
@SpringBootTest
@Transactional
@DisplayName("ScanResultRepository integration tests")
class ScanResultRepositoryTest {

    @Autowired ProjectRepository    projectRepository;
    @Autowired ScanResultRepository scanResultRepository;

    private Project project;

    @BeforeEach
    void setUp() {
        project = projectRepository.save(Project.builder().name("TestProject").build());
    }

    @Test
    @DisplayName("findCompletedByProjectId returns only COMPLETED scans in desc order")
    void findCompletedByProjectId_returnsOnlyCompletedScans() {
        saveScan("1.0", ScanStatus.COMPLETED, -5);
        saveScan("2.0", ScanStatus.FAILED, -3);
        saveScan("3.0", ScanStatus.COMPLETED, -1);

        List<ScanResult> results = scanResultRepository.findCompletedByProjectId(project.getId());

        assertThat(results).hasSize(2);
        assertThat(results.get(0).getVersion()).isEqualTo("3.0");
    }

    @Test
    @DisplayName("findByProjectIdAndVersion returns matching scan")
    void findByProjectIdAndVersion_returnsMatchingScan() {
        saveScan("1.5.0", ScanStatus.COMPLETED, -2);

        Optional<ScanResult> found =
                scanResultRepository.findByProjectIdAndVersion(project.getId(), "1.5.0");

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(ScanStatus.COMPLETED);
    }

    @Test
    @DisplayName("findLatestByProjectId returns newest scan regardless of status")
    void findLatestByProjectId_returnsNewestRegardlessOfStatus() {
        saveScan("1.0", ScanStatus.COMPLETED, -10);
        saveScan("2.0", ScanStatus.FAILED, -1);

        Optional<ScanResult> latest = scanResultRepository.findLatestByProjectId(project.getId());

        assertThat(latest).isPresent();
        assertThat(latest.get().getVersion()).isEqualTo("2.0");
    }

    @Test
    @DisplayName("findAllByProjectIdOrderByScannedAtDesc returns all scans in desc order")
    void findAllByProjectIdOrderByScannedAtDesc_returnsAllSortedDesc() {
        saveScan("1.0", ScanStatus.COMPLETED, -5);
        saveScan("2.0", ScanStatus.PENDING,   -3);
        saveScan("3.0", ScanStatus.FAILED,    -1);

        List<ScanResult> all = scanResultRepository.findAllByProjectIdOrderByScannedAtDesc(project.getId());

        assertThat(all).hasSize(3);
        assertThat(all.get(0).getVersion()).isEqualTo("3.0");
        assertThat(all.get(2).getVersion()).isEqualTo("1.0");
    }

    @Test
    @DisplayName("findByIdAndProjectId does not return a scan belonging to another project")
    void findByIdAndProjectId_preventsIdOR() {
        Project other = projectRepository.save(Project.builder().name("OtherProject").build());
        ScanResult otherScan = ScanResult.builder()
                .project(other).version("x").status(ScanStatus.COMPLETED).build();
        otherScan.setScannedAt(LocalDateTime.now());
        ScanResult saved = scanResultRepository.save(otherScan);

        Optional<ScanResult> result =
                scanResultRepository.findByIdAndProjectId(saved.getId(), project.getId());

        assertThat(result).isEmpty();
    }

    // ── helper ──────────────────────────────────────────────────────────

    private void saveScan(String version, ScanStatus status, int daysOffset) {
        ScanResult scan = ScanResult.builder()
                .project(project)
                .version(version)
                .status(status)
                .build();
        scan.setScannedAt(LocalDateTime.now().plusDays(daysOffset));
        scanResultRepository.save(scan);
    }
}