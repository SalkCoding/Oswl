package com.salkcoding.oswl.service.secretscan;

import com.salkcoding.oswl.dto.scan.CustomRuleSet;
import com.salkcoding.oswl.domain.enums.*;
import com.salkcoding.oswl.dto.scan.CustomScanRule;
import com.salkcoding.oswl.repository.scan.CustomRuleConfigurationRepository;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:custom-rule-publication;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS TEXT")
@WithMockUser(roles = "SYSTEM_ADMIN")
class CustomRulePublicationTest {
    @Autowired CustomScanRuleService service;
    @Autowired CustomRuleConfigurationRepository repository;
    @BeforeEach void clean() { repository.deleteAllInBatch(); }
    @Test void publishesCoherentRevisionRejectsStaleWriteAndCanDisable() {
        var rule = new CustomScanRule("company",ScanFindingType.IAC,RiskLevel.HIGH,"Company policy","public_access=true",".tf");
        var published = service.publish(new CustomRuleSet(-1,List.of(rule)));
        assertThat(published.revision()).isZero();
        assertThat(service.compiled()).hasSize(1);
        assertThatThrownBy(()->service.publish(new CustomRuleSet(-1,List.of())))
                .isInstanceOf(org.springframework.web.server.ResponseStatusException.class);
        assertThat(service.read().rules()).containsExactly(rule);
        var disabled = service.publish(new CustomRuleSet(published.revision(),List.of()));
        assertThat(disabled.revision()).isEqualTo(1);
        assertThat(service.compiled()).isEmpty();
    }
    @Test @WithMockUser(roles = "USER") void rejectsNonAdminEvenOnDirectServiceInvocation() {
        assertThatThrownBy(service::read).isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThatThrownBy(()->service.publish(new CustomRuleSet(-1,List.of())))
                .isInstanceOf(org.springframework.security.access.AccessDeniedException.class);
        assertThat(repository.count()).isZero();
    }
    @Autowired SecretIacScanService scanner;
    @Autowired com.salkcoding.oswl.service.gate.GatePolicyService gate;
    @Autowired com.salkcoding.oswl.repository.scan.ScanFindingRepository findings;
    @Autowired com.salkcoding.oswl.repository.scan.ScanResultRepository scans;
    @Autowired com.salkcoding.oswl.repository.project.ProjectRepository projects;
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path root;
    @Test void invalidStoredRulesCannotRollBackBuiltInFindings() throws Exception {
        repository.saveAndFlush(com.salkcoding.oswl.domain.entity.scan.CustomRuleConfiguration.create("invalid-json"));
        var project = projects.save(com.salkcoding.oswl.domain.entity.project.Project.builder().name("Scanner resilience").build());
        var scan = scans.save(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project).version("1").status(ScanStatus.COMPLETED).build());
        java.nio.file.Files.writeString(root.resolve("Dockerfile"), "FROM ubuntu:latest\nUSER root\n");
        scanner.scanAndPersist(root, scan.getId());
        var saved = findings.findAll().stream().filter(f -> f.getScanResult().getId().equals(scan.getId())).toList();
        assertThat(saved).anyMatch(f -> f.getRuleId().equals("custom-scan-incomplete"));
        assertThat(saved).anyMatch(f -> !f.getRuleId().startsWith("custom-") && f.getType() == ScanFindingType.IAC);
        assertThat(findings.hasIncompleteScanner(scan.getId(), project.getId())).isTrue();
        var result = gate.evaluate(project.getId(), new com.salkcoding.oswl.service.gate.GatePolicyService.GateOptions(scan.getId(),null,null,null,null,true,true,false));
        assertThat(result.passed()).isFalse();
        assertThat(result.coverage().complete()).isFalse();
    }

    @Autowired com.salkcoding.oswl.service.ingest.ScanIngestService ingest;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @Autowired SourceFindingStore findingStore;
    @Autowired SecretIacScanService builtInScans;

    @Test void findingLimitRemainsIncompleteAfterPersistence(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        java.nio.file.Files.writeString(root.resolve("many.tf"), "acl = \"public-read\"\n".repeat(301));
        var project = projects.save(com.salkcoding.oswl.domain.entity.project.Project.builder()
                .name("Finding limit " + java.util.UUID.randomUUID()).build());
        var scan = scans.save(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project)
                .version("fixture").status(ScanStatus.COMPLETED).build());
        assertThat(builtInScans.scanAndPersist(root, scan.getId())).isTrue();
        var stored = findings.findByScanResultIdAndProjectId(scan.getId(), project.getId());
        assertThat(stored.stream().filter(f -> f.getRuleId().equals("tf-public-s3-acl")).count()).isEqualTo(300);
        assertThat(stored.stream().filter(f -> f.getRuleId().equals("iac-scan-incomplete")).count()).isEqualTo(1);
        var result = gate.evaluate(project.getId(), new com.salkcoding.oswl.service.gate.GatePolicyService.GateOptions(
                scan.getId(), null, null, null, null, true, true, false));
        assertThat(result.passed()).isFalse();
        assertThat(result.coverage().complete()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void builtInSourceFailureReachesStoredGateCoverage(boolean missing, @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) {
        var project = projects.save(com.salkcoding.oswl.domain.entity.project.Project.builder()
                .name("Built-in coverage " + java.util.UUID.randomUUID()).build());
        var scan = scans.save(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project)
                .version("fixture").status(ScanStatus.COMPLETED).build());
        assertThat(builtInScans.scanAndPersist(missing ? root.resolve("missing") : root, scan.getId())).isTrue();
        assertThat(findings.hasIncompleteScanner(scan.getId(), project.getId())).isEqualTo(missing);
        var stored = findings.findByScanResultIdAndProjectId(scan.getId(), project.getId());
        if (missing) assertThat(stored).extracting(com.salkcoding.oswl.domain.entity.scan.ScanFinding::getRuleId)
                .containsExactlyInAnyOrder("secret-scan-incomplete", "iac-scan-incomplete");
        else assertThat(stored).isEmpty();
        var result = gate.evaluate(project.getId(), new com.salkcoding.oswl.service.gate.GatePolicyService.GateOptions(
                scan.getId(), null, null, null, null, true, true, false));
        assertThat(result.coverage().complete()).isEqualTo(!missing);
        assertThat(result.passed()).isEqualTo(!missing);
    }

    @Test void baselineQueryOrdersTimestampTiesAndExcludesOtherProjectsAndIncompleteScans() {
        var project = projects.save(com.salkcoding.oswl.domain.entity.project.Project.builder().name("Baseline order").build());
        var other = projects.save(com.salkcoding.oswl.domain.entity.project.Project.builder().name("Other baseline project").build());
        var time = java.time.LocalDateTime.now().withNano(0);
        var previous = scans.save(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project)
                .version("previous").scannedAt(time).status(ScanStatus.COMPLETED).build());
        scans.save(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(other)
                .version("foreign").scannedAt(time).status(ScanStatus.COMPLETED).build());
        scans.save(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project)
                .version("failed").scannedAt(time).status(ScanStatus.FAILED).build());
        var target = scans.save(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project)
                .version("target").scannedAt(time).status(ScanStatus.COMPLETED).build());
        scans.save(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project)
                .version("future").scannedAt(time).status(ScanStatus.COMPLETED).build());
        assertThat(scans.findPreviousCompleted(project.getId(),time,target.getId()))
                .hasValueSatisfying(scan -> assertThat(scan.getId()).isEqualTo(previous.getId()));
        assertThat(scans.findPreviousCompleted(project.getId(),time,previous.getId())).isEmpty();
    }

    @Test void futureScanCannotHideNewSecretsWhenGatingHistoricalScan() {
        var project = projects.save(com.salkcoding.oswl.domain.entity.project.Project.builder().name("Historical gate").build());
        var time = java.time.LocalDateTime.now().minusHours(2);
        var previous = scans.save(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project)
                .version("previous").scannedAt(time).status(ScanStatus.COMPLETED).build());
        var target = scans.save(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project)
                .version("target").scannedAt(time.plusMinutes(1)).status(ScanStatus.COMPLETED).build());
        for (int i=0;i<12;i++) {
            var future = scans.save(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project)
                    .version("future-"+i).scannedAt(time.plusMinutes(2+i)).status(ScanStatus.COMPLETED).build());
            findings.save(com.salkcoding.oswl.domain.entity.scan.ScanFinding.builder().scanResult(future)
                    .type(ScanFindingType.SECRET).ruleId("fixture-secret").filePath("config.txt")
                    .severity(RiskLevel.HIGH).description("Fixture secret finding").build());
        }
        findings.saveAndFlush(com.salkcoding.oswl.domain.entity.scan.ScanFinding.builder().scanResult(target)
                .type(ScanFindingType.SECRET).ruleId("fixture-secret").filePath("config.txt")
                .severity(RiskLevel.HIGH).description("Fixture secret finding").build());
        var result = gate.evaluate(project.getId(),new com.salkcoding.oswl.service.gate.GatePolicyService.GateOptions(
                target.getId(),null,null,null,null,true,false,true));
        assertThat(result.passed()).isFalse();
        assertThat(result.baselineVersion()).isEqualTo(previous.getVersion());
        assertThat(result.violations()).extracting(v -> v.type()).contains("SECRET");
    }

    @Test void identicalRetryKeepsOneScanAndChangedInputConflicts() {
        var project = projects.save(com.salkcoding.oswl.domain.entity.project.Project.builder().name("Retry identity").build());
        var payload = com.salkcoding.oswl.dto.scan.ScanPayload.create("main", List.of());
        payload.setIdempotencyKey("request-1");
        var original = ingest.ingest(project.getId(), payload);
        var retry = ingest.ingest(project.getId(), payload);
        assertThat(retry.getId()).isEqualTo(original.getId());
        assertThat(retry.getInputDigest()).isEqualTo(original.getInputDigest()).hasSize(64);
        var changed = com.salkcoding.oswl.dto.scan.ScanPayload.create("changed", List.of());
        changed.setIdempotencyKey("request-1");
        assertThatThrownBy(() -> ingest.ingest(project.getId(), changed))
                .isInstanceOf(com.salkcoding.oswl.exception.ConflictException.class);
        assertThat(scans.findById(original.getId()).orElseThrow().getVersion()).isEqualTo("main");
        var otherProject = projects.save(com.salkcoding.oswl.domain.entity.project.Project.builder().name("Separate retry namespace").build());
        assertThat(ingest.ingest(otherProject.getId(), payload).getId()).isNotEqualTo(original.getId());
        payload.setIdempotencyKey("request-2");
        assertThat(ingest.ingest(project.getId(), payload).getId()).isNotEqualTo(original.getId());
    }

    @Test void simultaneousRetriesShareOnePersistedScan() throws Exception {
        var project = projects.save(com.salkcoding.oswl.domain.entity.project.Project.builder().name("Concurrent retries").build());
        var payload = com.salkcoding.oswl.dto.scan.ScanPayload.create("main", List.of());
        payload.setIdempotencyKey("concurrent-1");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Long> request = () -> {
                ready.countDown();
                if (!start.await(10, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("Retry start timed out");
                return ingest.ingest(project.getId(), payload).getId();
            };
            var first = workers.submit(request);
            var second = workers.submit(request);
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(20, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(second.get(20, java.util.concurrent.TimeUnit.SECONDS));
        }
    }

    @Test void successfulSameVersionRescanPreservesOldIncompleteFindings() throws Exception {
        var project=projects.save(com.salkcoding.oswl.domain.entity.project.Project.builder().name("Rescan recovery").build());
        var scan=scans.save(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project).version("retry").status(ScanStatus.COMPLETED).build());
        findings.saveAndFlush(com.salkcoding.oswl.domain.entity.scan.ScanFinding.builder().scanResult(scan).type(ScanFindingType.IAC).ruleId("custom-scan-incomplete").severity(RiskLevel.HIGH).filePath(".").description("Incomplete").build());
        assertThat(gate.evaluate(project.getId(),com.salkcoding.oswl.service.gate.GatePolicyService.GateOptions.defaults()).passed()).isFalse();
        var retried=ingest.ingest(project.getId(),com.salkcoding.oswl.dto.scan.ScanPayload.create("retry",List.of()));
        assertThat(retried.getId()).isNotEqualTo(scan.getId());
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
        while(scans.findById(retried.getId()).orElseThrow().getStatus()!=ScanStatus.COMPLETED && System.nanoTime()<deadline) Thread.sleep(50);
        assertThat(scans.findById(retried.getId()).orElseThrow().getStatus()).isEqualTo(ScanStatus.COMPLETED);
        assertThat(findings.hasIncompleteScanner(retried.getId(),project.getId())).isFalse();
        assertThat(findings.hasIncompleteScanner(scan.getId(),project.getId())).isTrue();
        var original = gate.evaluate(project.getId(),new com.salkcoding.oswl.service.gate.GatePolicyService.GateOptions(scan.getId(),null,null,null,null,true,true,false));
        assertThat(original.passed()).isFalse();
        assertThat(original.coverage().complete()).isFalse();
        assertThat(gate.evaluate(project.getId(),com.salkcoding.oswl.service.gate.GatePolicyService.GateOptions.defaults()).passed()).isTrue();
    }
    @Test void interruptedCliRetryHasAnIndependentResultEvenWhenTheOldWorkerFinishes() throws Exception {
        var project=projects.save(com.salkcoding.oswl.domain.entity.project.Project.builder().name("CLI retry").build());
        var old=scans.save(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project).version("retry").status(ScanStatus.ANALYZING).build());
        var retried=ingest.ingest(project.getId(),com.salkcoding.oswl.dto.scan.ScanPayload.create("retry",List.of()));
        assertThat(retried.getId()).isNotEqualTo(old.getId());
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
        while(scans.findById(retried.getId()).orElseThrow().getStatus()!=ScanStatus.COMPLETED && System.nanoTime()<deadline) Thread.sleep(50);
        old.fail("Late failure from the interrupted attempt");
        scans.save(old);
        assertThat(scans.findById(retried.getId()).orElseThrow().getStatus()).isEqualTo(ScanStatus.COMPLETED);
        assertThat(scans.findByProjectIdAndVersion(project.getId(),"retry").orElseThrow().getId()).isEqualTo(retried.getId());
    }

    @Test void completingAnOldRunCannotRemoveAnotherPendingRunOrWriteStaleFindings() {
        var project=projects.save(com.salkcoding.oswl.domain.entity.project.Project.builder().name("Source generation").build());
        var scan=scans.save(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project).version("1").status(ScanStatus.COMPLETED).build());
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        Long first=tx.execute(status->scanner.markPending(scan.getId()));
        Long second=tx.execute(status->scanner.markPending(scan.getId()));
        scanner.completeSourceScan(scan.getId(),first);
        assertThat(findings.existsById(second)).isTrue();
        assertThat(findingStore.persist(scan.getId(),first,List.of(new com.salkcoding.oswl.dto.scan.ScanFindingCandidate(ScanFindingType.SECRET,"old",RiskLevel.HIGH,".",1,"Old",null)))).isFalse();
        scanner.completeSourceScan(scan.getId(),second);
        assertThat(findings.countByScanResultId(scan.getId())).isZero();
    }

    @Test void expiredSourceWorkerCannotShareTheRetriedResult() {
        var project=projects.save(com.salkcoding.oswl.domain.entity.project.Project.builder().name("Expired source retry").build());
        var old=scans.save(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project).version("retry").status(ScanStatus.COMPLETED).build());
        var tx=new org.springframework.transaction.support.TransactionTemplate(transactions);
        Long pending=tx.execute(status->scanner.markPending(old.getId()));
        var retried=ingest.ingest(project.getId(),com.salkcoding.oswl.dto.scan.ScanPayload.create("retry",List.of()));
        assertThat(retried.getId()).isNotEqualTo(old.getId());
        assertThat(findings.existsById(pending)).isTrue();
        scanner.completeSourceScan(old.getId(),pending);
        assertThat(findings.hasIncompleteScanner(retried.getId(),project.getId())).isFalse();
    }

}
