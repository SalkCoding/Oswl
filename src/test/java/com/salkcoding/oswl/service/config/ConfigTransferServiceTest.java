package com.salkcoding.oswl.service.config;

import com.salkcoding.oswl.auth.repository.RoleTemplateRepository;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.auth.service.CacheManagementService;
import com.salkcoding.oswl.auth.service.RoleTemplateService;
import com.salkcoding.oswl.domain.entity.org.Organization;
import com.salkcoding.oswl.domain.entity.org.Team;
import com.salkcoding.oswl.domain.entity.policy.Policy;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.enums.PolicyScopeType;
import com.salkcoding.oswl.dto.config.ConfigBundle;
import com.salkcoding.oswl.dto.config.ConfigBundle.PolicyExport;
import com.salkcoding.oswl.dto.config.ConfigImportResult;
import com.salkcoding.oswl.repository.ai.AiSettingRepository;
import com.salkcoding.oswl.repository.license.LicensePolicyRepository;
import com.salkcoding.oswl.repository.org.OrganizationRepository;
import com.salkcoding.oswl.repository.org.TeamRepository;
import com.salkcoding.oswl.repository.policy.PolicyRepository;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("ConfigTransferService 정책 계층 export/import 단위 테스트")
class ConfigTransferServiceTest {

    @Mock RoleTemplateService roleTemplateService;
    @Mock RoleTemplateRepository roleTemplateRepository;
    @Mock LicensePolicyRepository licensePolicyRepository;
    @Mock com.salkcoding.oswl.service.license.LicensePolicyService licensePolicyService;
    @Mock AiSettingRepository aiSettingRepository;
    @Mock CacheManagementService cacheManagementService;
    @Mock AuditLogService auditLogService;
    @Mock PolicyRepository policyRepository;
    @Mock OrganizationRepository organizationRepository;
    @Mock TeamRepository teamRepository;
    @Mock ProjectRepository projectRepository;

    @InjectMocks
    ConfigTransferService configTransferService;

    private static final Organization ORG = Organization.builder().id(1L).name("Acme Corp").build();
    private static final Team TEAM = Team.builder().id(2L).organization(ORG).name("Backend").build();
    private static final Project PROJECT = Project.builder().id(3L).name("api-server").team(TEAM).build();

    // ── export ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("export: 정책이 스코프를 ID가 아닌 이름으로 해석해 포함된다")
    void export_includesPoliciesWithNameResolvedScopes() {
        when(roleTemplateService.findAll()).thenReturn(List.of());
        when(licensePolicyRepository.findAll()).thenReturn(List.of());
        when(aiSettingRepository.findAll()).thenReturn(List.of());
        when(cacheManagementService.findAll()).thenReturn(List.of());
        when(policyRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(
                Policy.builder().scope(PolicyScopeType.ORGANIZATION).organization(ORG)
                        .name("Org baseline").locked(true).failOnSeverity("HIGH").build(),
                Policy.builder().scope(PolicyScopeType.TEAM).team(TEAM)
                        .name("Team policy").failOnKev(true).build(),
                Policy.builder().scope(PolicyScopeType.PROJECT).project(PROJECT)
                        .name("Project policy").failOnEpss(0.5).onlyNew(true).build()
        ));

        ConfigBundle bundle = configTransferService.export();

        assertThat(bundle.policies()).hasSize(3);
        PolicyExport org = bundle.policies().get(0);
        assertThat(org.scopeType()).isEqualTo("ORGANIZATION");
        assertThat(org.scopeName()).isEqualTo("Acme Corp");
        assertThat(org.name()).isEqualTo("Org baseline");
        assertThat(org.locked()).isTrue();
        assertThat(org.failOnSeverity()).isEqualTo("HIGH");
        assertThat(bundle.policies().get(1).scopeName()).isEqualTo("Backend");
        PolicyExport project = bundle.policies().get(2);
        assertThat(project.scopeName()).isEqualTo("api-server");
        assertThat(project.failOnEpss()).isEqualTo(0.5);
        assertThat(project.onlyNew()).isTrue();
    }

    // ── import: dry-run ───────────────────────────────────────────────────

    @Test
    @DisplayName("import dry-run: 정책 개수만 집계하고 아무것도 저장하지 않는다")
    void importDryRun_countsWithoutWriting() {
        when(teamRepository.findByName("Backend")).thenReturn(Optional.of(TEAM));
        when(policyRepository.findByTeamId(2L)).thenReturn(Optional.empty());

        ConfigImportResult result = configTransferService.importBundle(
                bundleWith(teamPolicy("Team policy")), true);

        assertThat(result.dryRun()).isTrue();
        assertThat(result.policiesCreated()).isEqualTo(1);
        assertThat(result.policiesUpdated()).isZero();
        assertThat(result.policiesSkippedUnresolved()).isZero();
        verify(policyRepository, never()).save(any());
    }

    // ── import: apply ─────────────────────────────────────────────────────

    @Test
    @DisplayName("import 적용: 생성 + 기존 정책 갱신 + 미해결 스코프 스킵을 함께 처리한다")
    void importApply_createsUpdatesAndSkipsUnresolved() {
        Policy existingProjectPolicy = Policy.builder()
                .scope(PolicyScopeType.PROJECT).project(PROJECT)
                .name("Old name").failOnSeverity("LOW").build();

        when(teamRepository.findByName("Backend")).thenReturn(Optional.of(TEAM));
        when(policyRepository.findByTeamId(2L)).thenReturn(Optional.empty());
        when(projectRepository.findAllByNameAndDeletedAtIsNull("api-server")).thenReturn(List.of(PROJECT));
        when(policyRepository.findByProjectId(3L)).thenReturn(Optional.of(existingProjectPolicy));
        when(projectRepository.findAllByNameAndDeletedAtIsNull("ghost")).thenReturn(List.of());

        ConfigImportResult result = configTransferService.importBundle(bundleWith(
                teamPolicy("Team policy"),
                projectPolicy("api-server", "Project policy"),
                projectPolicy("ghost", "Ghost policy")
        ), false);

        assertThat(result.policiesCreated()).isEqualTo(1);
        assertThat(result.policiesUpdated()).isEqualTo(1);
        assertThat(result.policiesSkippedUnresolved()).isEqualTo(1);
        assertThat(result.manualStepsRequired())
                .anyMatch(s -> s.contains("Ghost policy") && s.contains("ghost"));

        // create path: saved with the resolved team attached
        ArgumentCaptor<Policy> saved = ArgumentCaptor.forClass(Policy.class);
        verify(policyRepository, times(2)).save(saved.capture());
        Policy created = saved.getAllValues().stream()
                .filter(p -> p.getTeam() != null).findFirst().orElseThrow();
        assertThat(created.getTeam()).isSameAs(TEAM);
        assertThat(created.getScope()).isEqualTo(PolicyScopeType.TEAM);
        assertThat(created.getName()).isEqualTo("Team policy");

        // update path: fields overwritten on the existing row
        assertThat(existingProjectPolicy.getName()).isEqualTo("Project policy");
        assertThat(existingProjectPolicy.getFailOnSeverity()).isEqualTo("CRITICAL");

        verify(auditLogService).log(eq("CONFIG.IMPORT"), any(), any(), any(), contains("policies=1/1"));
    }

    @Test
    @DisplayName("import: 같은 이름의 활성 프로젝트가 여러 개면 추측하지 않고 스킵한다")
    void import_skipsAmbiguousProjectName() {
        Project dup = Project.builder().id(4L).name("api-server").team(TEAM).build();
        when(projectRepository.findAllByNameAndDeletedAtIsNull("api-server"))
                .thenReturn(List.of(PROJECT, dup));

        ConfigImportResult result = configTransferService.importBundle(
                bundleWith(projectPolicy("api-server", "Project policy")), false);

        assertThat(result.policiesCreated()).isZero();
        assertThat(result.policiesUpdated()).isZero();
        assertThat(result.policiesSkippedUnresolved()).isEqualTo(1);
        assertThat(result.manualStepsRequired())
                .anyMatch(s -> s.contains("Project policy") && s.contains("ambiguous"));
        verify(policyRepository, never()).save(any());
    }

    @Test
    @DisplayName("import: 조직 스코프는 싱글턴 조직에 매칭하고 이름 불일치는 manualSteps에 남긴다")
    void import_orgScopeMatchesSingletonWithNameMismatchNote() {
        when(organizationRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(ORG));
        when(policyRepository.findByOrganizationId(1L)).thenReturn(Optional.empty());

        ConfigImportResult result = configTransferService.importBundle(bundleWith(
                new PolicyExport("ORGANIZATION", "Different Org Name", "Org baseline", null,
                        true, true, "HIGH", null, null, null, null)
        ), false);

        assertThat(result.policiesCreated()).isEqualTo(1);
        assertThat(result.policiesSkippedUnresolved()).isZero();
        assertThat(result.manualStepsRequired())
                .anyMatch(s -> s.contains("Org baseline") && s.contains("name mismatch"));

        ArgumentCaptor<Policy> saved = ArgumentCaptor.forClass(Policy.class);
        verify(policyRepository).save(saved.capture());
        assertThat(saved.getValue().getOrganization()).isSameAs(ORG);
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private static ConfigBundle bundleWith(PolicyExport... policies) {
        return new ConfigBundle(null, null, null, null, null, null, List.of(policies), null);
    }

    private static PolicyExport teamPolicy(String name) {
        return new PolicyExport("TEAM", "Backend", name, null,
                false, true, null, true, null, null, null);
    }

    private static PolicyExport projectPolicy(String scopeName, String name) {
        return new PolicyExport("PROJECT", scopeName, name, null,
                false, true, "CRITICAL", null, null, null, null);
    }
}
