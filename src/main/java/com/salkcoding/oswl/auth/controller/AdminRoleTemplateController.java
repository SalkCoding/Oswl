package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.RoleTemplateDto;
import com.salkcoding.oswl.auth.dto.RoleTemplateRequest;
import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.service.RoleTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/admin/role-templates")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class AdminRoleTemplateController {

    private final RoleTemplateService roleTemplateService;

    @GetMapping
    public List<RoleTemplateDto> list() {
        return roleTemplateService.findAll();
    }

    @GetMapping("/permissions")
    public List<Map<String, String>> allPermissions() {
        return Arrays.stream(Permission.values())
                .map(p -> Map.of("code", p.name(), "description", p.getDescription()))
                .collect(Collectors.toList());
    }

    @PostMapping
    public RoleTemplateDto create(@RequestBody @Valid RoleTemplateRequest request) {
        return roleTemplateService.create(request);
    }

    @PutMapping("/{id}")
    public RoleTemplateDto update(@PathVariable Long id, @RequestBody RoleTemplateRequest request) {
        return roleTemplateService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        roleTemplateService.delete(id);
    }
}
