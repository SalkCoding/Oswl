package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.ProjectMemberControllerSpec;
import com.salkcoding.oswl.dto.AddProjectMemberRequest;
import com.salkcoding.oswl.dto.ProjectMemberDto;
import com.salkcoding.oswl.service.ProjectAccessService;
import com.salkcoding.oswl.service.ProjectMemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/projects/{projectId}/members")
@PreAuthorize("hasPermission(null, 'PROJECT_VIEW') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class ProjectMemberController implements ProjectMemberControllerSpec {

    private final ProjectMemberService projectMemberService;
    private final ProjectAccessService projectAccessService;

    @GetMapping
    public ResponseEntity<List<ProjectMemberDto>> list(@PathVariable Long projectId) {
        return ResponseEntity.ok(projectMemberService.listMembers(projectId));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'PROJECT_MEMBER_MANAGE') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<ProjectMemberDto> add(
            @PathVariable Long projectId,
            @Valid @RequestBody AddProjectMemberRequest request) {
        return ResponseEntity.ok(projectMemberService.addMember(projectId, request));
    }

    @DeleteMapping("/{userId}")
    @PreAuthorize("hasPermission(null, 'PROJECT_MEMBER_MANAGE') or hasRole('SYSTEM_ADMIN')")
    public ResponseEntity<Map<String, Boolean>> remove(
            @PathVariable Long projectId,
            @PathVariable Long userId) {
        projectMemberService.removeMember(projectId, userId);
        return ResponseEntity.ok(Map.of("removed", true));
    }
}
