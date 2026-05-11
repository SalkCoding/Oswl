package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.RoleTemplateDto;
import com.salkcoding.oswl.auth.dto.RoleTemplateRequest;
import com.salkcoding.oswl.auth.entity.RoleTemplate;
import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.repository.RoleTemplateRepository;
import com.salkcoding.oswl.aop.Auditable;
import lombok.RequiredArgsConstructor;
import com.salkcoding.oswl.auth.service.AuditLogService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleTemplateService {

    private final RoleTemplateRepository roleTemplateRepository;
    private final AuditLogService auditLogService;

    @Transactional(readOnly = true)
    public List<RoleTemplateDto> findAll() {
        return roleTemplateRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    @Auditable(action = "ROLE_TEMPLATE.CREATE", targetType = "ROLE_TEMPLATE",
               targetIdExpr = "#result.id.toString()", targetNameExpr = "#result.name")
    public RoleTemplateDto create(RoleTemplateRequest request) {
        if (roleTemplateRepository.existsByName(request.getName().trim())) {
            throw new IllegalArgumentException("이미 사용 중인 템플릿 이름입니다.");
        }
        RoleTemplate rt = RoleTemplate.builder()
                .name(request.getName().trim())
                .description(request.getDescription())
                .isBuiltIn(false)
                .permissions(parsePermissions(request.getPermissions()))
                .build();
        return toDto(roleTemplateRepository.save(rt));
    }

    @Transactional
    @Auditable(action = "ROLE_TEMPLATE.UPDATE", targetType = "ROLE_TEMPLATE",
               targetIdExpr = "#result.id.toString()", targetNameExpr = "#result.name")
    public RoleTemplateDto update(Long id, RoleTemplateRequest request) {
        RoleTemplate rt = roleTemplateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("템플릿을 찾을 수 없습니다."));
        if (request.getName() != null && !request.getName().isBlank()) {
            rt.setName(request.getName().trim());
        }
        rt.setDescription(request.getDescription());
        rt.setPermissions(parsePermissions(request.getPermissions()));
        return toDto(rt);
    }

    @Transactional
    public void delete(Long id) {
        RoleTemplate rt = roleTemplateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("템플릿을 찾을 수 없습니다."));
        if (rt.isBuiltIn()) {
            throw new IllegalStateException("기본 제공 템플릿은 삭제할 수 없습니다.");
        }
        // 해당 템플릿을 가진 유저들을 비활성화 처리
        List<Long> affectedUserIds = roleTemplateRepository.findUserIdsByTemplateId(id);
        if (!affectedUserIds.isEmpty()) {
            roleTemplateRepository.deactivateUsers(affectedUserIds);
        }
        String name = rt.getName();
        roleTemplateRepository.delete(rt);
        auditLogService.log("ROLE_TEMPLATE.DELETE", "ROLE_TEMPLATE", id.toString(), name, null);
    }

    private Set<Permission> parsePermissions(Set<String> permissionNames) {
        if (permissionNames == null || permissionNames.isEmpty()) {
            return EnumSet.noneOf(Permission.class);
        }
        Set<Permission> set = EnumSet.noneOf(Permission.class);
        for (String n : permissionNames) {
            try {
                set.add(Permission.valueOf(n));
            } catch (IllegalArgumentException ignored) {
                // skip unknown
            }
        }
        return set;
    }

    private RoleTemplateDto toDto(RoleTemplate rt) {
        return RoleTemplateDto.builder()
                .id(rt.getId())
                .name(rt.getName())
                .description(rt.getDescription())
                .builtIn(rt.isBuiltIn())
                .permissions(rt.getPermissions().stream().map(Enum::name).collect(Collectors.toSet()))
                .userCount(roleTemplateRepository.countUsersByTemplateId(rt.getId()))
                .build();
    }
}
