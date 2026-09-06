package com.salkcoding.oswl.service.secretscan;

import com.salkcoding.oswl.dto.scan.CustomRuleSet;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.enums.*;
import com.salkcoding.oswl.dto.scan.CustomScanRule;
import com.salkcoding.oswl.exception.InvalidRequestException;
import com.salkcoding.oswl.repository.scan.CustomRuleConfigurationRepository;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.access.AccessDeniedException;
import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CustomScanRuleTest {
    @TempDir Path root;
    CustomScanRuleService service = mock(CustomScanRuleService.class);
    private CustomScanRule rule(String regex) {return new CustomScanRule("company-token",ScanFindingType.SECRET,RiskLevel.HIGH,"Company token detected",regex,".txt");}
    private void deploy(CustomScanRule rule) {
        CustomScanRuleService.validate(List.of(rule));
        when(service.compiled()).thenReturn(List.of(new CustomScanRuleService.CompiledRule(rule,com.google.re2j.Pattern.compile(rule.regex()),7)));
    }
    @Test void matchesWithoutPersistingSourceTextAndIncludesRevision() throws Exception {
        deploy(rule("PRIVATE-[A-Z]{8}"));
        Files.writeString(root.resolve("config.txt"),"token=PRIVATE-ABCDEFGH\n");
        var results=new CustomRuleScanner(service).scan(root);
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().ruleId()).isEqualTo("custom-company-token@7");
        assertThat(results.getFirst().fingerprint()).isNull();
        assertThat(results.toString()).doesNotContain("PRIVATE-ABCDEFGH");
    }
    @Test void rejectsUnsupportedRegexDuplicateIdsAndEmptyMatches() {
        for (String regex:List.of("(a)\\1","(?=secret)",".*","["))
            assertThatThrownBy(()->CustomScanRuleService.validate(List.of(rule(regex)))).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(()->CustomScanRuleService.validate(List.of(rule("x"),rule("y")))).isInstanceOf(InvalidRequestException.class);
    }
    @Test void pathologicalBacktrackingInputHasBoundedExecutionAndLargeLinesAreExplicit() throws Exception {
        deploy(rule("(a+)+b"));
        Files.writeString(root.resolve("config.txt"),"a".repeat(8000)+"!\n"+"x".repeat(9000));
        var results=org.junit.jupiter.api.Assertions.assertTimeoutPreemptively(Duration.ofSeconds(2),()->new CustomRuleScanner(service).scan(root));
        assertThat(results).hasSize(1);
        assertThat(results.getFirst().ruleId()).isEqualTo("custom-scan-incomplete@7");
    }
    @Test void serviceRejectsUnauthenticatedRuleReadAndPublish() {
        var real=new CustomScanRuleService(mock(CustomRuleConfigurationRepository.class),mock(AuditLogService.class));
        assertThatThrownBy(real::read).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(()->real.publish(new CustomRuleSet(-1,List.of()))).isInstanceOf(AccessDeniedException.class);
    }
}
