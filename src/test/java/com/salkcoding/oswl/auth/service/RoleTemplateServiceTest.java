package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.RoleTemplateDto;
import com.salkcoding.oswl.auth.dto.RoleTemplateRequest;
import com.salkcoding.oswl.auth.entity.RoleTemplate;
import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.repository.RoleTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RoleTemplateService 단위 테스트")
class RoleTemplateServiceTest {

    @Mock RoleTemplateRepository roleTemplateRepository;
    @Mock AuditLogService        auditLogService;

    @InjectMocks RoleTemplateService roleTemplateService;

    // ── findAll ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("findAll: 저장된 템플릿 목록을 DTO로 반환한다")
    void findAll_returnsDtos() {
        RoleTemplate rt = buildTemplate(1L, "Developer", false, EnumSet.of(Permission.PROJECT_VIEW));
        when(roleTemplateRepository.findAll()).thenReturn(List.of(rt));
        when(roleTemplateRepository.countUsersByTemplateId(1L)).thenReturn(3L);

        List<RoleTemplateDto> result = roleTemplateService.findAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getName()).isEqualTo("Developer");
        assertThat(result.get(0).getUserCount()).isEqualTo(3L);
        assertThat(result.get(0).getPermissions()).contains("PROJECT_VIEW");
    }

    @Test
    @DisplayName("findAll: 빈 목록이면 빈 리스트를 반환한다")
    void findAll_empty() {
        when(roleTemplateRepository.findAll()).thenReturn(List.of());

        assertThat(roleTemplateService.findAll()).isEmpty();
    }

    // ── create ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("create: 이름 중복이면 IllegalArgumentException을 던진다")
    void create_duplicateName_throws() {
        RoleTemplateRequest req = new RoleTemplateRequest();
        req.setName("  Developer  ");

        when(roleTemplateRepository.existsByName("Developer")).thenReturn(true);

        assertThatThrownBy(() -> roleTemplateService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already in use");
    }

    @Test
    @DisplayName("create: 정상 생성 시 DTO가 반환된다")
    void create_success() {
        RoleTemplateRequest req = new RoleTemplateRequest();
        req.setName("  CustomRole  ");
        req.setPermissions(Set.of("PROJECT_VIEW", "SCAN_VIEW"));

        when(roleTemplateRepository.existsByName("CustomRole")).thenReturn(false);

        RoleTemplate saved = buildTemplate(5L, "CustomRole", false, EnumSet.of(Permission.PROJECT_VIEW, Permission.SCAN_VIEW));
        when(roleTemplateRepository.save(any())).thenReturn(saved);
        when(roleTemplateRepository.countUsersByTemplateId(5L)).thenReturn(0L);

        RoleTemplateDto dto = roleTemplateService.create(req);

        assertThat(dto.getName()).isEqualTo("CustomRole");
        assertThat(dto.isBuiltIn()).isFalse();
        verify(roleTemplateRepository).save(argThat(rt -> rt.getName().equals("CustomRole") && !rt.isBuiltIn()));
    }

    @Test
    @DisplayName("create: 권한 목록이 null이면 빈 권한 집합으로 저장된다")
    void create_nullPermissions_savesEmpty() {
        RoleTemplateRequest req = new RoleTemplateRequest();
        req.setName("Empty");
        req.setPermissions(null);

        when(roleTemplateRepository.existsByName("Empty")).thenReturn(false);

        RoleTemplate saved = buildTemplate(6L, "Empty", false, EnumSet.noneOf(Permission.class));
        when(roleTemplateRepository.save(any())).thenReturn(saved);
        when(roleTemplateRepository.countUsersByTemplateId(6L)).thenReturn(0L);

        RoleTemplateDto dto = roleTemplateService.create(req);
        assertThat(dto.getPermissions()).isEmpty();
    }

    // ── update ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("update: 없는 id이면 IllegalArgumentException을 던진다")
    void update_notFound_throws() {
        when(roleTemplateRepository.findById(99L)).thenReturn(Optional.empty());

        RoleTemplateRequest req = new RoleTemplateRequest();
        req.setName("X");

        assertThatThrownBy(() -> roleTemplateService.update(99L, req))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("update: 이름과 권한이 수정된다")
    void update_success() {
        RoleTemplate rt = buildTemplate(1L, "OldName", false, EnumSet.of(Permission.PROJECT_VIEW));
        when(roleTemplateRepository.findById(1L)).thenReturn(Optional.of(rt));
        when(roleTemplateRepository.countUsersByTemplateId(1L)).thenReturn(0L);

        RoleTemplateRequest req = new RoleTemplateRequest();
        req.setName("NewName");
        req.setPermissions(Set.of("SCAN_VIEW"));

        roleTemplateService.update(1L, req);

        assertThat(rt.getName()).isEqualTo("NewName");
        assertThat(rt.getPermissions()).contains(Permission.SCAN_VIEW);
    }

    @Test
    @DisplayName("update: name이 blank이면 기존 이름이 유지된다")
    void update_blankName_keepsExisting() {
        RoleTemplate rt = buildTemplate(1L, "OriginalName", false, EnumSet.noneOf(Permission.class));
        when(roleTemplateRepository.findById(1L)).thenReturn(Optional.of(rt));
        when(roleTemplateRepository.countUsersByTemplateId(1L)).thenReturn(0L);

        RoleTemplateRequest req = new RoleTemplateRequest();
        req.setName("   ");
        req.setPermissions(null);

        roleTemplateService.update(1L, req);

        assertThat(rt.getName()).isEqualTo("OriginalName");
    }

    // ── delete ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("delete: 없는 id이면 IllegalArgumentException을 던진다")
    void delete_notFound_throws() {
        when(roleTemplateRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> roleTemplateService.delete(99L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("delete: builtIn 템플릿은 삭제할 수 없다")
    void delete_builtIn_throws() {
        RoleTemplate rt = buildTemplate(1L, "Admin", true, EnumSet.allOf(Permission.class));
        when(roleTemplateRepository.findById(1L)).thenReturn(Optional.of(rt));

        assertThatThrownBy(() -> roleTemplateService.delete(1L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Built-in");
    }

    @Test
    @DisplayName("delete: 연결된 유저가 없으면 deactivateUsers를 호출하지 않는다")
    void delete_noAffectedUsers() {
        RoleTemplate rt = buildTemplate(2L, "Custom", false, EnumSet.noneOf(Permission.class));
        when(roleTemplateRepository.findById(2L)).thenReturn(Optional.of(rt));
        when(roleTemplateRepository.findUserIdsByTemplateId(2L)).thenReturn(List.of());

        roleTemplateService.delete(2L);

        verify(roleTemplateRepository).delete(rt);
        verify(roleTemplateRepository, never()).deactivateUsers(anyList());
        verify(auditLogService).log(eq("ROLE_TEMPLATE.DELETE"), any(), eq("2"), any(), isNull());
    }

    @Test
    @DisplayName("delete: 연결된 유저가 있으면 deactivateUsers를 호출한다")
    void delete_withAffectedUsers_deactivates() {
        RoleTemplate rt = buildTemplate(3L, "Custom", false, EnumSet.noneOf(Permission.class));
        when(roleTemplateRepository.findById(3L)).thenReturn(Optional.of(rt));
        when(roleTemplateRepository.findUserIdsByTemplateId(3L)).thenReturn(List.of(10L, 11L));

        roleTemplateService.delete(3L);

        verify(roleTemplateRepository).deactivateUsers(List.of(10L, 11L));
        verify(roleTemplateRepository).delete(rt);
    }

    // ── helper ────────────────────────────────────────────────────────────

    private RoleTemplate buildTemplate(Long id, String name, boolean builtIn, Set<Permission> perms) {
        return RoleTemplate.builder()
                .id(id)
                .name(name)
                .isBuiltIn(builtIn)
                .permissions(perms)
                .build();
    }
}
