package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.RoleTemplateDto;
import com.salkcoding.oswl.auth.dto.RoleTemplateRequest;
import com.salkcoding.oswl.auth.entity.RoleTemplate;
import com.salkcoding.oswl.auth.enums.Permission;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.salkcoding.oswl.auth.repository.RoleTemplateRepository;
import com.salkcoding.oswl.aop.Auditable;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class RoleTemplateService {

    private static final String CACHE_KEY_ALL = "all";

    private final RoleTemplateRepository roleTemplateRepository;
    private final AuditLogService auditLogService;

    private final Cache<String, List<RoleTemplateDto>> roleTemplateCache = Caffeine.newBuilder()
            .expireAfterWrite(Duration.ofHours(1))
            .maximumSize(10)
            .recordStats()
            .build();

    @Transactional(readOnly = true)
    public List<RoleTemplateDto> findAll() {
        List<RoleTemplateDto> cached = roleTemplateCache.getIfPresent(CACHE_KEY_ALL);
        if (cached != null) {
            return List.copyOf(cached);
        }
        List<RoleTemplateDto> dtos = roleTemplateRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
        roleTemplateCache.put(CACHE_KEY_ALL, dtos);
        return List.copyOf(dtos);
    }

    @Transactional
    @Auditable(action = "ROLE_TEMPLATE.CREATE", targetType = "ROLE_TEMPLATE",
               targetIdExpr = "#result.id.toString()", targetNameExpr = "#result.name")
    public RoleTemplateDto create(RoleTemplateRequest request) {
        if (roleTemplateRepository.existsByName(request.getName().trim())) {
            throw new IllegalArgumentException("Template name is already in use.");
        }
        RoleTemplate rt = RoleTemplate.builder()
                .name(request.getName().trim())
                .description(request.getDescription())
                .isBuiltIn(false)
                .permissions(parsePermissions(request.getPermissions()))
                .build();
        RoleTemplateDto dto = toDto(roleTemplateRepository.save(rt));
        roleTemplateCache.invalidateAll();
        return dto;
    }

    @Transactional
    @Auditable(action = "ROLE_TEMPLATE.UPDATE", targetType = "ROLE_TEMPLATE",
               targetIdExpr = "#result.id.toString()", targetNameExpr = "#result.name")
    public RoleTemplateDto update(Long id, RoleTemplateRequest request) {
        RoleTemplate rt = roleTemplateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template not found."));
        if (request.getName() != null && !request.getName().isBlank()) {
            rt.setName(request.getName().trim());
        }
        rt.setDescription(request.getDescription());
        rt.setPermissions(parsePermissions(request.getPermissions()));
        RoleTemplateDto dto = toDto(rt);
        roleTemplateCache.invalidateAll();
        return dto;
    }

    /**
     * Create-or-update by name — used by config import, which has no template id
     * to key off (ids are not portable across instances). Built-in templates are never modified
     * this way; a same-named built-in in the bundle is silently skipped.
     */
    @Transactional
    public RoleTemplateDto upsertByName(String name, String description, Set<String> permissionNames) {
        RoleTemplate existing = roleTemplateRepository.findByName(name).orElse(null);
        if (existing != null && existing.isBuiltIn()) {
            return toDto(existing);
        }
        RoleTemplateRequest req = new RoleTemplateRequest();
        req.setName(name);
        req.setDescription(description);
        req.setPermissions(permissionNames);
        return existing != null ? update(existing.getId(), req) : create(req);
    }

    @Transactional
    public void delete(Long id) {
        RoleTemplate rt = roleTemplateRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Template not found."));
        if (rt.isBuiltIn()) {
            throw new IllegalStateException("Built-in templates cannot be deleted.");
        }
        // Deactivate users assigned to this template
        List<Long> affectedUserIds = roleTemplateRepository.findUserIdsByTemplateId(id);
        if (!affectedUserIds.isEmpty()) {
            roleTemplateRepository.deactivateUsers(affectedUserIds);
        }
        String name = rt.getName();
        roleTemplateRepository.delete(rt);
        roleTemplateCache.invalidateAll();
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
