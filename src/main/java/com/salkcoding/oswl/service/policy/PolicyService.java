package com.salkcoding.oswl.service.policy;

import com.salkcoding.oswl.aop.Auditable;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.domain.entity.org.Organization;
import com.salkcoding.oswl.domain.entity.policy.Policy;
import com.salkcoding.oswl.domain.entity.policy.PolicyException;
import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.org.Team;
import com.salkcoding.oswl.domain.enums.PolicyExceptionStatus;
import com.salkcoding.oswl.domain.enums.PolicyExceptionTargetType;
import com.salkcoding.oswl.domain.enums.PolicyScopeType;
import com.salkcoding.oswl.dto.policy.*;
import com.salkcoding.oswl.exception.OutboundUrlBlockedException;
import com.salkcoding.oswl.repository.org.OrganizationRepository;
import com.salkcoding.oswl.repository.policy.PolicyExceptionRepository;
import com.salkcoding.oswl.repository.policy.PolicyRepository;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import com.salkcoding.oswl.repository.org.TeamRepository;
import com.salkcoding.oswl.security.OutboundUrlValidator;
import com.salkcoding.oswl.service.gate.GatePolicyService.GateOptions;
import com.salkcoding.oswl.service.git.GitCloneCredentials;
import com.salkcoding.oswl.service.git.GitCloneExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Manages Policy-as-Code rows and resolves the effective gate thresholds for a project.
 *
 * Resolution order is organization → team → project. An explicit field on a lower level
 * overrides the same field from a higher level unless a higher level has locked it.
 * When no policy row exists, {@link #resolveGateOptions(Long)} returns an all-null
 * {@link GateOptions} so {@link GatePolicyService} falls back to its {@code @Value} defaults.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PolicyService {

    private final PolicyRepository policyRepository;
    private final PolicyExceptionRepository policyExceptionRepository;
    private final OrganizationRepository organizationRepository;
    private final TeamRepository teamRepository;
    private final ProjectRepository projectRepository;
    private final AuditLogService auditLogService;
    private final GitCloneExecutor gitCloneExecutor;
    private final OutboundUrlValidator outboundUrlValidator;

    private static final String YAML_GATE_KEY = "gate";

    /**
     * Loader for user-supplied policy YAML (import + GitOps sync). {@code SafeConstructor}
     * restricts deserialization to plain Java types (Map/List/String/Number/Boolean/Date) —
     * the default {@link Yaml} constructor allows YAML type tags to instantiate arbitrary
     * classes on the classpath, which is a known remote-code-execution vector for
     * attacker-controlled YAML.
     */
    private static final Yaml SAFE_YAML = new Yaml(new SafeConstructor(new LoaderOptions()));

    // ── CRUD ─────────────────────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PolicyDto> findAll() {
        return policyRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional(readOnly = true)
    public PolicyDto findById(Long id) {
        return toDto(policyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Policy not found: " + id)));
    }

    @Transactional(readOnly = true)
    public PolicyDto findByScope(PolicyScopeType scopeType, Long scopeId) {
        return toDto(findPolicyByScope(scopeType, scopeId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Policy not found for " + scopeType + "=" + scopeId)));
    }

    @Transactional
    @Auditable(action = "POLICY.CREATE", targetType = "POLICY",
               targetIdExpr = "#result.id.toString()", targetNameExpr = "#result.name")
    public PolicyDto create(PolicyRequest request) {
        validateScope(request.scopeType(), request.scopeId());
        assertNoExistingPolicy(request.scopeType(), request.scopeId());
        Policy policy = buildPolicy(request);
        return toDto(policyRepository.save(policy));
    }

    @Transactional
    @Auditable(action = "POLICY.UPDATE", targetType = "POLICY",
               targetIdExpr = "#id.toString()", targetNameExpr = "#result.name")
    public PolicyDto update(Long id, PolicyRequest request) {
        Policy policy = policyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Policy not found: " + id));
        validateScope(request.scopeType(), request.scopeId());
        if (!matchesExistingScope(policy, request.scopeType(), request.scopeId())) {
            assertNoExistingPolicy(request.scopeType(), request.scopeId());
            policy = policy.toBuilder()
                    .scope(request.scopeType())
                    .organization(resolveOrganization(request.scopeType(), request.scopeId()))
                    .team(resolveTeam(request.scopeType(), request.scopeId()))
                    .project(resolveProject(request.scopeType(), request.scopeId()))
                    .build();
        }
        policy.update(request.name(), request.description(), request.locked(), request.enabled(),
                request.failOnSeverity(), request.failOnKev(), request.failOnEpss(),
                request.failOnLicenseViolation(), request.onlyNew());
        return toDto(policy);
    }

    @Transactional
    @Auditable(action = "POLICY.DELETE", targetType = "POLICY",
               targetIdExpr = "#id.toString()", targetNameExpr = "#result.name", when = Auditable.When.BEFORE)
    public PolicyDto delete(Long id) {
        Policy policy = policyRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Policy not found: " + id));
        policyRepository.delete(policy);
        return toDto(policy);
    }

    // ── Effective policy / gate options ──────────────────────────────────

    @Transactional(readOnly = true)
    public EffectivePolicyDto getEffectivePolicy(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        PolicyHierarchy h = loadHierarchy(project);
        GateOptions merged = merge(h);
        return EffectivePolicyDto.builder()
                .projectId(projectId)
                .organizationPolicy(h.org().map(this::toDto).orElse(null))
                .teamPolicy(h.team().map(this::toDto).orElse(null))
                .projectPolicy(h.project().map(this::toDto).orElse(null))
                .failOnSeverity(merged.failOnSeverity())
                .failOnKev(merged.failOnKev())
                .failOnEpss(merged.failOnEpss())
                .failOnLicenseViolation(merged.failOnLicenseViolation())
                .onlyNew(merged.onlyNew())
                .build();
    }

    /**
     * Returns a {@link GateOptions} populated with the effective policy values.
     * Fields that are not defined by any policy in the hierarchy remain null so that
     * {@link GatePolicyService} can fall back to its {@code @Value} defaults.
     */
    @Transactional(readOnly = true)
    public GateOptions resolveGateOptions(Long projectId) {
        Project project = projectRepository.findById(projectId)
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + projectId));
        return merge(loadHierarchy(project));
    }

    // ── YAML export / import ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public String exportEffectiveAsYaml(Long projectId) {
        EffectivePolicyDto effective = getEffectivePolicy(projectId);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("name", "effective-policy");
        root.put("description", "Effective policy for project " + projectId);
        root.put("locked", false);
        root.put("enabled", true);
        root.put(YAML_GATE_KEY, toYamlMap(effective));
        return new Yaml().dump(root);
    }

    @Transactional(readOnly = true)
    public String exportAllAsYaml() {
        List<Map<String, Object>> policies = findAll().stream()
                .map(this::toYamlMap)
                .toList();
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("policies", policies);
        return new Yaml().dump(root);
    }

    @Transactional
    @Auditable(action = "POLICY.IMPORT", targetType = "POLICY",
               targetIdExpr = "'bulk'", targetNameExpr = "'YAML import'")
    public List<PolicyDto> importFromYaml(String yaml) {
        Object parsed = SAFE_YAML.load(yaml);
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("YAML must contain a map.");
        }
        List<PolicyDto> result = new ArrayList<>();
        Object policies = map.get("policies");
        if (policies instanceof List<?> list) {
            for (Object item : list) {
                result.add(importSingle(item));
            }
        } else {
            result.add(importSingle(parsed));
        }
        return result;
    }

    // ── GitOps sync ──────────────────────────────────────────────────────

    @Transactional
    @Auditable(action = "POLICY.GITOPS_SYNC", targetType = "PROJECT",
               targetIdExpr = "#request.projectId().toString()",
               targetNameExpr = "#result.name")
    public PolicyDto syncFromGitRepository(PolicyGitOpsRequest request) {
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + request.projectId()));
        if (request.repositoryUrl() == null || request.repositoryUrl().isBlank()) {
            throw new IllegalArgumentException("Repository URL is required.");
        }
        // SSRF guard: repositoryUrl is attacker-controllable (any project member with
        // POLICY_MANAGE can trigger this), so it must go through the same loopback/private-
        // network/cloud-metadata block as every other user-supplied outbound URL in the app.
        try {
            outboundUrlValidator.validateHttpUrl(request.repositoryUrl());
        } catch (OutboundUrlBlockedException e) {
            throw new IllegalArgumentException(e.getMessage());
        }
        Path tempDir = createTempDir();
        try {
            GitCloneCredentials creds = (request.accessToken() != null && !request.accessToken().isBlank())
                    ? new GitCloneCredentials("x-access-token", request.accessToken())
                    : null;
            gitCloneExecutor.clone(request.repositoryUrl(), creds, request.branch(), tempDir,
                    "policy-gitops-" + project.getId());
            Path policyFile = tempDir.resolve(".oswl/policy.yaml");
            if (!Files.exists(policyFile)) {
                throw new IllegalArgumentException("Repository does not contain .oswl/policy.yaml");
            }
            String yaml = Files.readString(policyFile);
            PolicyDto imported = importSingle(SAFE_YAML.load(yaml));
            PolicyRequest projectScoped = new PolicyRequest(
                    PolicyScopeType.PROJECT, project.getId(), imported.getName(), imported.getDescription(),
                    imported.isLocked(), imported.isEnabled(), imported.getFailOnSeverity(),
                    imported.getFailOnKev(), imported.getFailOnEpss(), imported.getFailOnLicenseViolation(),
                    imported.getOnlyNew());
            return saveOrUpdateScoped(projectScoped);
        } catch (Exception e) {
            log.error("[PolicyGitOps] Failed to sync projectId={} from {}: {}",
                    project.getId(), request.repositoryUrl(), e.getMessage());
            throw new RuntimeException("GitOps policy sync failed: " + e.getMessage(), e);
        } finally {
            deleteQuietly(tempDir);
        }
    }

    // ── Exceptions (waivers) ─────────────────────────────────────────────

    @Transactional(readOnly = true)
    public List<PolicyExceptionDto> listExceptions(Long projectId) {
        return policyExceptionRepository.findByProjectIdOrderByCreatedAtDesc(projectId).stream()
                .map(this::toDto)
                .toList();
    }

    /**
     * Approved, unexpired exceptions for a project — the set {@link GatePolicyService}
     * checks a violation against before letting it fail the gate.
     */
    @Transactional(readOnly = true)
    public List<PolicyException> findActiveExceptions(Long projectId) {
        LocalDateTime now = LocalDateTime.now();
        return policyExceptionRepository
                .findByProjectIdAndStatusOrderByCreatedAtDesc(projectId, PolicyExceptionStatus.APPROVED).stream()
                .filter(ex -> ex.getExpiry().isAfter(now))
                .toList();
    }

    @Transactional
    @Auditable(action = "POLICY_EXCEPTION.REQUEST", targetType = "PROJECT",
               targetIdExpr = "#request.projectId().toString()", targetNameExpr = "#result.reason")
    public PolicyExceptionDto requestException(PolicyExceptionRequest request, Long requesterUserId, String requesterName) {
        Project project = projectRepository.findById(request.projectId())
                .orElseThrow(() -> new IllegalArgumentException("Project not found: " + request.projectId()));
        if (request.reason() == null || request.reason().isBlank()) {
            throw new IllegalArgumentException("A reason is required.");
        }
        if (request.expiry() == null || !request.expiry().isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Expiry must be a future date.");
        }
        PolicyException exception = PolicyException.builder()
                .project(project)
                .requesterUserId(requesterUserId)
                .requesterName(requesterName)
                .reason(request.reason())
                .expiry(request.expiry())
                .targetType(request.targetType() != null ? request.targetType() : PolicyExceptionTargetType.ALL)
                .targetId(request.targetId())
                .componentCoordinate(request.componentCoordinate())
                .build();
        return toDto(policyExceptionRepository.save(exception));
    }

    @Transactional
    @Auditable(action = "POLICY_EXCEPTION.APPROVE", targetType = "POLICY_EXCEPTION",
               targetIdExpr = "#id.toString()", targetNameExpr = "#result.reason")
    public PolicyExceptionDto approveException(Long id, Long approverUserId, String approverName) {
        PolicyException exception = policyExceptionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Policy exception not found: " + id));
        exception.approve(approverUserId, approverName);
        return toDto(exception);
    }

    @Transactional
    @Auditable(action = "POLICY_EXCEPTION.REVOKE", targetType = "POLICY_EXCEPTION",
               targetIdExpr = "#id.toString()", targetNameExpr = "#result.reason")
    public PolicyExceptionDto revokeException(Long id) {
        PolicyException exception = policyExceptionRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Policy exception not found: " + id));
        exception.revoke();
        return toDto(exception);
    }

    private PolicyExceptionDto toDto(PolicyException e) {
        return PolicyExceptionDto.builder()
                .id(e.getId())
                .projectId(e.getProject().getId())
                .requesterUserId(e.getRequesterUserId())
                .requesterName(e.getRequesterName())
                .approverUserId(e.getApproverUserId())
                .approverName(e.getApproverName())
                .reason(e.getReason())
                .expiry(e.getExpiry())
                .status(e.getStatus())
                .targetType(e.getTargetType())
                .targetId(e.getTargetId())
                .componentCoordinate(e.getComponentCoordinate())
                .createdAt(e.getCreatedAt())
                .updatedAt(e.getUpdatedAt())
                .approvedAt(e.getApprovedAt())
                .revokedAt(e.getRevokedAt())
                .build();
    }

    // ── Internal helpers ─────────────────────────────────────────────────

    private PolicyDto saveOrUpdateScoped(PolicyRequest request) {
        Optional<Policy> existing = findPolicyByScope(request.scopeType(), request.scopeId());
        if (existing.isPresent()) {
            return update(existing.get().getId(), request);
        }
        return create(request);
    }

    private PolicyDto importSingle(Object parsed) {
        if (!(parsed instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Each policy must be a YAML map.");
        }
        PolicyScopeType scopeType = parseScopeType(getString(map, "scope"));
        Long scopeId = parseLong(getString(map, "scopeId"));
        if (scopeType == null || scopeId == null) {
            throw new IllegalArgumentException("Policy YAML must contain 'scope' and 'scopeId'.");
        }
        Map<?, ?> gate = map.get(YAML_GATE_KEY) instanceof Map<?, ?> m ? m : map;
        PolicyRequest request = new PolicyRequest(
                scopeType,
                scopeId,
                getString(map, "name"),
                getString(map, "description"),
                Boolean.TRUE.equals(map.get("locked")),
                !Boolean.FALSE.equals(map.get("enabled")),
                getString(gate, "failOnSeverity"),
                (Boolean) gate.get("failOnKev"),
                (Double) gate.get("failOnEpss"),
                (Boolean) gate.get("failOnLicenseViolation"),
                (Boolean) gate.get("onlyNew")
        );
        return saveOrUpdateScoped(request);
    }

    private Policy buildPolicy(PolicyRequest request) {
        return Policy.builder()
                .scope(request.scopeType())
                .organization(resolveOrganization(request.scopeType(), request.scopeId()))
                .team(resolveTeam(request.scopeType(), request.scopeId()))
                .project(resolveProject(request.scopeType(), request.scopeId()))
                .name(request.name())
                .description(request.description())
                .locked(request.locked())
                .enabled(request.enabled())
                .failOnSeverity(request.failOnSeverity())
                .failOnKev(request.failOnKev())
                .failOnEpss(request.failOnEpss())
                .failOnLicenseViolation(request.failOnLicenseViolation())
                .onlyNew(request.onlyNew())
                .build();
    }

    private Optional<Policy> findPolicyByScope(PolicyScopeType scopeType, Long scopeId) {
        return switch (scopeType) {
            case ORGANIZATION -> policyRepository.findByOrganizationId(scopeId);
            case TEAM -> policyRepository.findByTeamId(scopeId);
            case PROJECT -> policyRepository.findByProjectId(scopeId);
        };
    }

    private void assertNoExistingPolicy(PolicyScopeType scopeType, Long scopeId) {
        if (findPolicyByScope(scopeType, scopeId).isPresent()) {
            throw new IllegalStateException(
                    "A policy already exists for " + scopeType + "=" + scopeId + ". Use update instead.");
        }
    }

    private void validateScope(PolicyScopeType scopeType, Long scopeId) {
        if (scopeType == null || scopeId == null) {
            throw new IllegalArgumentException("Scope type and scope id are required.");
        }
        switch (scopeType) {
            case ORGANIZATION -> organizationRepository.findById(scopeId)
                    .orElseThrow(() -> new IllegalArgumentException("Organization not found: " + scopeId));
            case TEAM -> teamRepository.findById(scopeId)
                    .orElseThrow(() -> new IllegalArgumentException("Team not found: " + scopeId));
            case PROJECT -> projectRepository.findById(scopeId)
                    .orElseThrow(() -> new IllegalArgumentException("Project not found: " + scopeId));
        }
    }

    private boolean matchesExistingScope(Policy policy, PolicyScopeType scopeType, Long scopeId) {
        return policy.getScope() == scopeType && scopeId.equals(switch (scopeType) {
            case ORGANIZATION -> policy.getOrganization() != null ? policy.getOrganization().getId() : null;
            case TEAM -> policy.getTeam() != null ? policy.getTeam().getId() : null;
            case PROJECT -> policy.getProject() != null ? policy.getProject().getId() : null;
        });
    }

    private Organization resolveOrganization(PolicyScopeType scopeType, Long scopeId) {
        return scopeType == PolicyScopeType.ORGANIZATION
                ? organizationRepository.findById(scopeId).orElse(null)
                : null;
    }

    private Team resolveTeam(PolicyScopeType scopeType, Long scopeId) {
        return scopeType == PolicyScopeType.TEAM
                ? teamRepository.findById(scopeId).orElse(null)
                : null;
    }

    private Project resolveProject(PolicyScopeType scopeType, Long scopeId) {
        return scopeType == PolicyScopeType.PROJECT
                ? projectRepository.findById(scopeId).orElse(null)
                : null;
    }

    private PolicyHierarchy loadHierarchy(Project project) {
        Organization org = resolveOrganizationFor(project);
        Optional<Policy> orgPolicy = org != null ? policyRepository.findByOrganizationId(org.getId()) : Optional.empty();
        Optional<Policy> teamPolicy = project.getTeam() != null
                ? policyRepository.findByTeamId(project.getTeam().getId())
                : Optional.empty();
        Optional<Policy> projectPolicy = policyRepository.findByProjectId(project.getId());
        return new PolicyHierarchy(orgPolicy, teamPolicy, projectPolicy);
    }

    private Organization resolveOrganizationFor(Project project) {
        if (project.getTeam() != null && project.getTeam().getOrganization() != null) {
            return project.getTeam().getOrganization();
        }
        return organizationRepository.findAll().stream().findFirst().orElse(null);
    }

    private GateOptions merge(PolicyHierarchy h) {
        MutableGate m = new MutableGate();
        applyLevel(h.org(), m);
        applyLevel(h.team(), m);
        applyLevel(h.project(), m);
        return new GateOptions(null, m.failOnSeverity, m.failOnKev, m.failOnEpss,
                m.failOnLicenseViolation, m.onlyNew, null, null);
    }

    private void applyLevel(Optional<Policy> policyOpt, MutableGate m) {
        policyOpt.filter(Policy::isEnabled).ifPresent(policy -> {
            applyField(policy.getFailOnSeverity(), "severity", policy.isLocked(), m);
            applyField(policy.getFailOnKev(), "kev", policy.isLocked(), m);
            applyField(policy.getFailOnEpss(), "epss", policy.isLocked(), m);
            applyField(policy.getFailOnLicenseViolation(), "license", policy.isLocked(), m);
            applyField(policy.getOnlyNew(), "onlyNew", policy.isLocked(), m);
        });
    }

    private void applyField(Object value, String field, boolean locked, MutableGate m) {
        if (value == null) {
            return;
        }
        if (!m.locked.contains(field)) {
            switch (field) {
                case "severity" -> m.failOnSeverity = (String) value;
                case "kev" -> m.failOnKev = (Boolean) value;
                case "epss" -> m.failOnEpss = (Double) value;
                case "license" -> m.failOnLicenseViolation = (Boolean) value;
                case "onlyNew" -> m.onlyNew = (Boolean) value;
            }
        }
        if (locked) {
            m.locked.add(field);
        }
    }

    private PolicyDto toDto(Policy policy) {
        Long scopeId = switch (policy.getScope()) {
            case ORGANIZATION -> policy.getOrganization() != null ? policy.getOrganization().getId() : null;
            case TEAM -> policy.getTeam() != null ? policy.getTeam().getId() : null;
            case PROJECT -> policy.getProject() != null ? policy.getProject().getId() : null;
        };
        String scopeName = switch (policy.getScope()) {
            case ORGANIZATION -> policy.getOrganization() != null ? policy.getOrganization().getName() : null;
            case TEAM -> policy.getTeam() != null ? policy.getTeam().getName() : null;
            case PROJECT -> policy.getProject() != null ? policy.getProject().getName() : null;
        };
        return PolicyDto.builder()
                .id(policy.getId())
                .scope(policy.getScope())
                .scopeId(scopeId)
                .scopeName(scopeName)
                .name(policy.getName())
                .description(policy.getDescription())
                .locked(policy.isLocked())
                .enabled(policy.isEnabled())
                .failOnSeverity(policy.getFailOnSeverity())
                .failOnKev(policy.getFailOnKev())
                .failOnEpss(policy.getFailOnEpss())
                .failOnLicenseViolation(policy.getFailOnLicenseViolation())
                .onlyNew(policy.getOnlyNew())
                .createdAt(policy.getCreatedAt())
                .updatedAt(policy.getUpdatedAt())
                .build();
    }

    private Map<String, Object> toYamlMap(EffectivePolicyDto effective) {
        Map<String, Object> gate = new LinkedHashMap<>();
        if (effective.getFailOnSeverity() != null) gate.put("failOnSeverity", effective.getFailOnSeverity());
        gate.put("failOnKev", effective.isFailOnKev());
        if (effective.getFailOnEpss() != null) gate.put("failOnEpss", effective.getFailOnEpss());
        gate.put("failOnLicenseViolation", effective.isFailOnLicenseViolation());
        gate.put("onlyNew", effective.isOnlyNew());
        return gate;
    }

    private Map<String, Object> toYamlMap(PolicyDto dto) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("scope", dto.getScope().name());
        root.put("scopeId", dto.getScopeId());
        root.put("name", dto.getName());
        root.put("description", dto.getDescription());
        root.put("locked", dto.isLocked());
        root.put("enabled", dto.isEnabled());
        Map<String, Object> gate = new LinkedHashMap<>();
        if (dto.getFailOnSeverity() != null) gate.put("failOnSeverity", dto.getFailOnSeverity());
        if (dto.getFailOnKev() != null) gate.put("failOnKev", dto.getFailOnKev());
        if (dto.getFailOnEpss() != null) gate.put("failOnEpss", dto.getFailOnEpss());
        if (dto.getFailOnLicenseViolation() != null) gate.put("failOnLicenseViolation", dto.getFailOnLicenseViolation());
        if (dto.getOnlyNew() != null) gate.put("onlyNew", dto.getOnlyNew());
        root.put(YAML_GATE_KEY, gate);
        return root;
    }

    private String getString(Map<?, ?> map, String key) {
        Object value = map.get(key);
        return value != null ? value.toString() : null;
    }

    private Long parseLong(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid numeric value: " + s);
        }
    }

    private PolicyScopeType parseScopeType(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return PolicyScopeType.valueOf(s.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid scope: " + s);
        }
    }

    private Path createTempDir() {
        try {
            return Files.createTempDirectory("oswl-policy-gitops-");
        } catch (Exception e) {
            throw new RuntimeException("Could not create temporary directory", e);
        }
    }

    private void deleteQuietly(Path dir) {
        try {
            if (dir == null || !Files.exists(dir)) return;
            Files.walk(dir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (Exception ignored) {
                        }
                    });
        } catch (Exception e) {
            log.warn("[PolicyGitOps] Could not clean up temp dir {}: {}", dir, e.getMessage());
        }
    }

    private record PolicyHierarchy(Optional<Policy> org, Optional<Policy> team, Optional<Policy> project) {}

    private static class MutableGate {
        String failOnSeverity;
        Boolean failOnKev;
        Double failOnEpss;
        Boolean failOnLicenseViolation;
        Boolean onlyNew;
        final Set<String> locked = new HashSet<>();
    }
}
