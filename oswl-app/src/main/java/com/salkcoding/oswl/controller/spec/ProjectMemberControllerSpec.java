package com.salkcoding.oswl.controller.spec;

import com.salkcoding.oswl.dto.AddProjectMemberRequest;
import com.salkcoding.oswl.dto.ProjectMemberDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

@Tag(name = "Project Members", description = "Per-project team membership management.")
@SecurityRequirement(name = "sessionAuth")
public interface ProjectMemberControllerSpec {

    @Operation(summary = "List project members")
    ResponseEntity<List<ProjectMemberDto>> list(
            @Parameter(description = "Project ID") Long projectId);

    @Operation(summary = "Add a project member by email")
    ResponseEntity<ProjectMemberDto> add(
            @Parameter(description = "Project ID") Long projectId,
            AddProjectMemberRequest request);

    @Operation(summary = "Remove a project member")
    ResponseEntity<Map<String, Boolean>> remove(
            @Parameter(description = "Project ID") Long projectId,
            @Parameter(description = "User ID to remove") Long userId);
}
