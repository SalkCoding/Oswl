package com.salkcoding.oswl;

import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.domain.entity.Cve;
import com.salkcoding.oswl.domain.entity.OswlComponent;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.*;
import com.salkcoding.oswl.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * local 프로파일 기동 시 샘플 프로젝트와 스캔 데이터를 자동으로 생성한다.
 * production 환경에서는 실행되지 않는다.
 */
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final ProjectRepository     projectRepository;
    private final ScanResultRepository  scanResultRepository;
    private final ComponentRepository   componentRepository;
    private final CveRepository         cveRepository;
    private final ApiKeyRepository      apiKeyRepository;

    @Override
    @Transactional
    public void run(String @NonNull ... args) {
        if (projectRepository.count() > 0) {
            log.info("[Init] 이미 데이터가 존재합니다. 초기화를 건너뜁니다.");
            return;
        }

        log.info("[Init] 샘플 데이터를 생성합니다...");

        // ── 프로젝트 1 ──────────────────────────────────────────────────────
        Project p1 = projectRepository.save(
                Project.builder().name("Project 1").build());

        ScanResult scan1 = ScanResult.builder()
                .project(p1).version("1.2.5").status(ScanStatus.COMPLETED).build();
        scan1.setScannedAt(LocalDateTime.of(2026, 4, 4, 10, 0));
        scanResultRepository.save(scan1);

        // Component: Android Arch-Common
        OswlComponent archCommon = componentRepository.save(OswlComponent.builder()
                .scanResult(scan1).name("Android Arch-Common").version("2.2.0-beta01")
                .dependencyInfo("Direct (6) + Transitive (1) · Projects (7)")
                .reviewed(true).patchability(Patchability.PATCHABLE)
                .licenseStatus(LicenseStatus.WARN)
                .licenseName("Creative Commons Attribution Share Alike 4.0").build());

        cveRepository.saveAll(List.of(
                Cve.builder().component(archCommon).cveId("CVE-2024-11053")
                        .severity(RiskLevel.CRITICAL).cvssScore(9.8).type("RCE")
                        .discoveredOn("2024-06-15").affects("Direct dep.").fixVersion("2.2.1-alpha01")
                        .aiSummary("An attacker can remotely execute arbitrary code on your server without authentication.")
                        .build(),
                Cve.builder().component(archCommon).cveId("CVE-2024-11054")
                        .severity(RiskLevel.CRITICAL).cvssScore(9.5).type("Injection")
                        .discoveredOn("2024-06-12").affects("Direct dep.").fixVersion("2.2.1-alpha01")
                        .build(),
                Cve.builder().component(archCommon).cveId("CVE-2024-11055")
                        .severity(RiskLevel.HIGH).cvssScore(8.2).type("XSS")
                        .discoveredOn("2024-06-10").affects("Transitive dep.").fixVersion("2.2.1-alpha01")
                        .build()
        ));

        componentRepository.save(OswlComponent.builder()
                .scanResult(scan1).name("A Simple Utility for showing Tooltips").version("0.1.6")
                .dependencyInfo("Direct (3) + Transitive (0) · Projects (2)")
                .reviewed(false).patchability(Patchability.NON_PATCHABLE)
                .licenseStatus(LicenseStatus.OK).licenseName("Apache-2.0").build());

        // API 키 발급
        apiKeyRepository.save(ApiKey.builder()
                .project(p1)
                .token("oswl_SAMPLE_KEY_PROJECT_1_DO_NOT_USE_IN_PROD")
                .label("Local Dev Key")
                .build());

        p1.updateLastScanned("1.2.5", LocalDateTime.of(2026, 4, 4, 10, 0));

        // ── 프로젝트 2~6 (리스크 데이터만) ────────────────────────────────────
        createSimpleProject("Project 2", "0.9.0", 2, 8,  15, 90, 1, 4, 6, 9,  LocalDateTime.of(2026, 4, 3, 9, 0));
        createSimpleProject("Project 3", "2.0.0", 0, 1,  5,  30, 0, 2, 3, 8,  LocalDateTime.of(2026, 4, 2, 8, 0));
        createSimpleProject("Project 4", "3.1.2", 8, 15, 25, 140, 4, 8, 10, 15, LocalDateTime.of(2026, 4, 1, 7, 0));
        createSimpleProject("Project 5", "1.0.0", 1, 3,  8,  40, 0, 1, 4, 7,  LocalDateTime.of(2026, 3, 31, 6, 0));
        createSimpleProject("Project 6", "4.2.0", 3, 7,  12, 80, 2, 5, 6, 10, LocalDateTime.of(2026, 3, 30, 5, 0));

        log.info("[Init] 샘플 데이터 생성 완료.");
    }

    private void createSimpleProject(String name, String version,
                                     int sCrit, int sHigh, int sMed, int sLow,
                                     int lCrit, int lHigh, int lMed, int lLow,
                                     LocalDateTime scannedAt) {
        Project project = projectRepository.save(Project.builder().name(name).build());
        ScanResult scan = ScanResult.builder()
                .project(project).version(version).status(ScanStatus.COMPLETED).build();
        scan.setScannedAt(scannedAt);
        scanResultRepository.save(scan);

        // 보안 CVE를 가진 더미 컴포넌트 생성
        addDummyComponents(scan, sCrit, sHigh, sMed, sLow, lCrit, lHigh, lMed, lLow);
        project.updateLastScanned(version, scannedAt);
    }

    private void addDummyComponents(ScanResult scan,
                                    int sCrit, int sHigh, int sMed, int sLow,
                                    int lCrit, int lHigh, int lMed, int lLow) {
        double[] cvssScores = {9.5, 7.5, 5.0, 2.0};
        RiskLevel[] levels = {RiskLevel.CRITICAL, RiskLevel.HIGH, RiskLevel.MEDIUM, RiskLevel.LOW};
        int[] counts = {sCrit, sHigh, sMed, sLow};

        for (int lvl = 0; lvl < 4; lvl++) {
            if (counts[lvl] == 0) continue;
            OswlComponent comp = componentRepository.save(OswlComponent.builder()
                    .scanResult(scan)
                    .name("Dummy-Lib-" + levels[lvl].name().toLowerCase())
                    .version("1.0.0")
                    .licenseStatus(LicenseStatus.OK)
                    .licenseName("MIT")
                    .patchability(Patchability.PATCHABLE)
                    .build());
            for (int i = 0; i < counts[lvl]; i++) {
                cveRepository.save(Cve.builder()
                        .component(comp)
                        .cveId("CVE-DUMMY-" + levels[lvl].name().charAt(0) + i)
                        .severity(levels[lvl])
                        .cvssScore(cvssScores[lvl])
                        .type("Generic")
                        .build());
            }
        }

        // 라이선스 위험 더미
        LicenseStatus[] licStatuses = {LicenseStatus.VIOLATION, LicenseStatus.WARN, LicenseStatus.WARN, LicenseStatus.OK};
        int[] licCounts = {lCrit, lHigh, lMed, lLow};
        for (int lvl = 0; lvl < 4; lvl++) {
            if (licCounts[lvl] == 0) continue;
            for (int i = 0; i < licCounts[lvl]; i++) {
                componentRepository.save(OswlComponent.builder()
                        .scanResult(scan)
                        .name("Lic-Comp-" + licStatuses[lvl].name() + "-" + i)
                        .version("1.0.0")
                        .licenseStatus(licStatuses[lvl])
                        .licenseName("Custom License")
                        .patchability(Patchability.UNKNOWN)
                        .build());
            }
        }
    }
}
