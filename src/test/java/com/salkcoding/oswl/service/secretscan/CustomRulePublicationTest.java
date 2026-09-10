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
    @Test void successfulSameVersionRescanClearsOldIncompleteFindings() throws Exception {
        var project=projects.save(com.salkcoding.oswl.domain.entity.project.Project.builder().name("Rescan recovery").build());
        var scan=scans.save(com.salkcoding.oswl.domain.entity.scan.ScanResult.builder().project(project).version("retry").status(ScanStatus.COMPLETED).build());
        findings.saveAndFlush(com.salkcoding.oswl.domain.entity.scan.ScanFinding.builder().scanResult(scan).type(ScanFindingType.IAC).ruleId("custom-scan-incomplete").severity(RiskLevel.HIGH).filePath(".").description("Incomplete").build());
        assertThat(gate.evaluate(project.getId(),com.salkcoding.oswl.service.gate.GatePolicyService.GateOptions.defaults()).passed()).isFalse();
        var retried=ingest.ingest(project.getId(),com.salkcoding.oswl.dto.scan.ScanPayload.create("retry",List.of()));
        assertThat(retried.getId()).isEqualTo(scan.getId());
        long deadline=System.nanoTime()+java.time.Duration.ofSeconds(10).toNanos();
        while(scans.findById(scan.getId()).orElseThrow().getStatus()!=ScanStatus.COMPLETED && System.nanoTime()<deadline) Thread.sleep(50);
        assertThat(findings.hasIncompleteScanner(scan.getId(),project.getId())).isFalse();
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
