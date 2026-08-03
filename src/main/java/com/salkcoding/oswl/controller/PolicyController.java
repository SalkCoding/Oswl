package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.controller.spec.PolicyControllerSpec;
import com.salkcoding.oswl.dto.policy.EffectivePolicyDto;
import com.salkcoding.oswl.dto.policy.PolicyDto;
import com.salkcoding.oswl.dto.policy.PolicyExceptionDto;
import com.salkcoding.oswl.dto.policy.PolicyExceptionRequest;
import com.salkcoding.oswl.dto.policy.PolicyGitOpsRequest;
import com.salkcoding.oswl.dto.policy.PolicyRequest;
import com.salkcoding.oswl.service.policy.PolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/policies")
@PreAuthorize("hasPermission(null, 'POLICY_MANAGE') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class PolicyController implements PolicyControllerSpec {

    private final PolicyService policyService;

    @GetMapping
    public List<PolicyDto> list() {
        return policyService.findAll();
    }

    @GetMapping("/{id}")
    public PolicyDto get(@PathVariable Long id) {
        return policyService.findById(id);
    }

    @PostMapping
    public PolicyDto create(@Valid @RequestBody PolicyRequest request) {
        return policyService.create(request);
    }

    @PutMapping("/{id}")
    public PolicyDto update(@PathVariable Long id, @Valid @RequestBody PolicyRequest request) {
        return policyService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public PolicyDto delete(@PathVariable Long id) {
        return policyService.delete(id);
    }

    @GetMapping("/export")
    public String exportAll() {
        return policyService.exportAllAsYaml();
    }

    @PostMapping("/import")
    public List<PolicyDto> importYaml(@RequestBody ImportRequest request) {
        return policyService.importFromYaml(request.yaml());
    }

    @PostMapping("/gitops-sync")
    public PolicyDto gitopsSync(@Valid @RequestBody PolicyGitOpsRequest request) {
        return policyService.syncFromGitRepository(request);
    }

    @GetMapping("/effective/{projectId}")
    public EffectivePolicyDto effective(@PathVariable Long projectId) {
        return policyService.getEffectivePolicy(projectId);
    }

    @GetMapping("/effective/{projectId}/export")
    public String exportEffective(@PathVariable Long projectId) {
        return policyService.exportEffectiveAsYaml(projectId);
    }

    @GetMapping("/exceptions/{projectId}")
    public List<PolicyExceptionDto> listExceptions(@PathVariable Long projectId) {
        return policyService.listExceptions(projectId);
    }

    @PostMapping("/exceptions")
    public PolicyExceptionDto requestException(@Valid @RequestBody PolicyExceptionRequest request,
                                               @AuthenticationPrincipal OswlUserPrincipal principal) {
        Long userId = principal != null ? principal.getUserId() : null;
        String name = principal != null ? principal.getDisplayName() : "unknown";
        return policyService.requestException(request, userId, name);
    }

    @PostMapping("/exceptions/{id}/approve")
    public PolicyExceptionDto approveException(@PathVariable Long id,
                                               @AuthenticationPrincipal OswlUserPrincipal principal) {
        Long userId = principal != null ? principal.getUserId() : null;
        String name = principal != null ? principal.getDisplayName() : "unknown";
        return policyService.approveException(id, userId, name);
    }

    @PostMapping("/exceptions/{id}/revoke")
    public PolicyExceptionDto revokeException(@PathVariable Long id) {
        return policyService.revokeException(id);
    }
}
