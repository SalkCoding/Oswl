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
}
