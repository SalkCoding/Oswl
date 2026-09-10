package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.dto.policy.EffectivePolicyDto;
import com.salkcoding.oswl.dto.policy.PolicyDto;
import com.salkcoding.oswl.dto.policy.PolicyExceptionDto;
import com.salkcoding.oswl.dto.policy.PolicyExceptionRequest;
import com.salkcoding.oswl.dto.policy.PolicyGitOpsRequest;
import com.salkcoding.oswl.dto.policy.PolicyRequest;
import com.salkcoding.oswl.dto.policy.PolicyScopeOptionsDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;

@Tag(name = "Policy as Code", description = "Organization/team/project security-gate policy hierarchy and waiver "
        + "(policy exception) approval workflow. Requires POLICY_MANAGE or SYSTEM_ADMIN.")
public interface PolicyControllerSpec {

    @Operation(summary = "List all policies")
    List<PolicyDto> list();

    @Operation(summary = "Get a policy by id")
    @ApiResponses({@ApiResponse(responseCode = "404", description = "Not found", content = @Content)})
    PolicyDto get(@Parameter(description = "Policy id", required = true) @PathVariable Long id);

    @Operation(summary = "List available scopes (organization/teams/projects) for the policy scope selector")
    PolicyScopeOptionsDto scopeOptions();

    @Operation(summary = "Create a policy at an organization, team, or project scope")
    @ApiResponses({@ApiResponse(responseCode = "409", description = "A policy already exists for that scope", content = @Content)})
    PolicyDto create(@Valid @RequestBody PolicyRequest request);

    @Operation(summary = "Update a policy")
    PolicyDto update(@PathVariable Long id, @Valid @RequestBody PolicyRequest request);

    @Operation(summary = "Delete a policy")
    PolicyDto delete(@PathVariable Long id);

    @Operation(summary = "Export all policies as GitOps-compatible YAML")
    String exportAll();

    @Operation(summary = "Import one or more policies from YAML (upserts by scope)")
    List<PolicyDto> importYaml(@RequestBody ImportRequest request);

    @Operation(summary = "Pull .oswl/policy.yaml from a Git repository and apply it as the project's policy")
    PolicyDto gitopsSync(@Valid @RequestBody PolicyGitOpsRequest request);

    @Operation(summary = "Resolve the effective (org → team → project, override-aware) policy for a project")
    EffectivePolicyDto effective(@Parameter(description = "Project id", required = true) @PathVariable Long projectId);

    @Operation(summary = "Export a project's effective policy as YAML")
    String exportEffective(@PathVariable Long projectId);

    @Operation(summary = "List policy exceptions (waivers) requested for a project")
    List<PolicyExceptionDto> listExceptions(@PathVariable Long projectId);

    @Operation(summary = "Request a policy exception (waiver) for a CVE, license violation, or whole component")
    PolicyExceptionDto requestException(@Valid @RequestBody PolicyExceptionRequest request,
                                        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal);

    @Operation(summary = "Approve a pending policy exception — it starts suppressing matching gate findings immediately")
    PolicyExceptionDto approveException(@PathVariable Long id,
                                        @Parameter(hidden = true) @AuthenticationPrincipal OswlUserPrincipal principal);

    @Operation(summary = "Revoke a pending or approved policy exception")
    PolicyExceptionDto revokeException(@PathVariable Long id);

    @Schema(description = "Raw policy YAML to import")
    record ImportRequest(String yaml) {}
}
