package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.AdminProjectControllerSpec;
import com.salkcoding.oswl.dto.AdminProjectRefDto;
import com.salkcoding.oswl.repository.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/projects")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminProjectController implements AdminProjectControllerSpec {

    private final ProjectRepository projectRepository;

    @GetMapping
    public List<AdminProjectRefDto> listActiveProjects() {
        return projectRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc().stream()
                .map(p -> new AdminProjectRefDto(p.getId(), p.getName()))
                .toList();
    }
}
