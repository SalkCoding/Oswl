package com.salkcoding.oswl.auth.service;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@Tag(TestTags.AUTH)
@Tag(TestTags.FAST)
@ExtendWith(MockitoExtension.class)
@DisplayName("RoleTemplateBootstrapService 단위 테스트")
class RoleTemplateBootstrapServiceTest {

    @Mock RoleTemplateRepository roleTemplateRepository;

    @InjectMocks RoleTemplateBootstrapService roleTemplateBootstrapService;

    private static EnumSet<Permission> developerPermissions() {
        return EnumSet.of(
                Permission.PROJECT_VIEW,
                Permission.PROJECT_CREATE,
                Permission.PROJECT_MEMBER_MANAGE,
                Permission.PROJECT_UPDATE,
                Permission.SCAN_SUBMIT,
                Permission.SCAN_VIEW,
                Permission.SCAN_HISTORY_VIEW,
                Permission.SECURITY_CENTER_VIEW,
                Permission.SECURITY_CENTER_UPDATE_STATUS,
                Permission.SECURITY_CENTER_EXPORT,
                Permission.LICENSE_VIEW,
                Permission.LICENSE_EXPORT,
                Permission.COMPONENT_DETAIL_VIEW,
                Permission.VERSION_DIFF_VIEW,
                Permission.RISK_TREND_VIEW,
                Permission.SETTINGS_VCS_MANAGE,
                Permission.SETTINGS_CLI_KEY_MANAGE,
                Permission.SETTINGS_NOTIFICATION_MANAGE
        );
    }

    @Test
    @DisplayName("ensureBuiltInTemplates: 템플릿이 없으면 3개를 저장한다")
    void ensureBuiltInTemplates_createsAllThree_whenNoneExist() {
        when(roleTemplateRepository.findByName("Admin")).thenReturn(Optional.empty());
        when(roleTemplateRepository.findByName("Developer")).thenReturn(Optional.empty());
        when(roleTemplateRepository.findByName("Viewer")).thenReturn(Optional.empty());

        roleTemplateBootstrapService.ensureBuiltInTemplates();

        verify(roleTemplateRepository, times(3)).save(any(RoleTemplate.class));
    }

    @Test
    @DisplayName("ensureBuiltInTemplates: 권한이 최신이면 저장하지 않는다")
    void ensureBuiltInTemplates_skipsAll_whenPermissionsCurrent() {
        when(roleTemplateRepository.findByName("Admin")).thenReturn(Optional.of(
                RoleTemplate.builder().name("Admin").isBuiltIn(true)
                        .permissions(EnumSet.allOf(Permission.class)).build()));
        when(roleTemplateRepository.findByName("Developer")).thenReturn(Optional.of(
                RoleTemplate.builder().name("Developer").isBuiltIn(true)
                        .permissions(developerPermissions()).build()));
        when(roleTemplateRepository.findByName("Viewer")).thenReturn(Optional.of(
                RoleTemplate.builder().name("Viewer").isBuiltIn(true)
                        .permissions(EnumSet.of(
                                Permission.PROJECT_VIEW,
                                Permission.SCAN_VIEW,
                                Permission.SCAN_HISTORY_VIEW,
                                Permission.SECURITY_CENTER_VIEW,
                                Permission.LICENSE_VIEW,
                                Permission.LICENSE_EXPORT,
                                Permission.COMPONENT_DETAIL_VIEW,
                                Permission.VERSION_DIFF_VIEW,
                                Permission.RISK_TREND_VIEW)).build()));

        roleTemplateBootstrapService.ensureBuiltInTemplates();

        verify(roleTemplateRepository, never()).save(any());
    }

    @Test
    @DisplayName("ensureBuiltInTemplates: Admin만 없으면 1개만 저장한다")
    void ensureBuiltInTemplates_savesOnlyMissing() {
        when(roleTemplateRepository.findByName("Admin")).thenReturn(Optional.empty());
        when(roleTemplateRepository.findByName("Developer")).thenReturn(Optional.of(
                RoleTemplate.builder().name("Developer").isBuiltIn(true)
                        .permissions(developerPermissions()).build()));
        when(roleTemplateRepository.findByName("Viewer")).thenReturn(Optional.of(
                RoleTemplate.builder().name("Viewer").isBuiltIn(true)
                        .permissions(EnumSet.of(Permission.PROJECT_VIEW)).build()));

        roleTemplateBootstrapService.ensureBuiltInTemplates();

        verify(roleTemplateRepository, times(1)).save(argThat(rt ->
                rt.getName().equals("Admin") && rt.isBuiltIn()));
    }

    @Test
    @DisplayName("ensureBuiltInTemplates: 기존 Developer에 누락 권한을 병합한다")
    void ensureBuiltInTemplates_mergesDeveloperPermissions() {
        RoleTemplate dev = RoleTemplate.builder().name("Developer").isBuiltIn(true)
                .permissions(EnumSet.of(Permission.PROJECT_VIEW, Permission.SCAN_SUBMIT))
                .build();
        when(roleTemplateRepository.findByName("Admin")).thenReturn(Optional.of(
                RoleTemplate.builder().name("Admin").isBuiltIn(true)
                        .permissions(EnumSet.allOf(Permission.class)).build()));
        when(roleTemplateRepository.findByName("Developer")).thenReturn(Optional.of(dev));
        when(roleTemplateRepository.findByName("Viewer")).thenReturn(Optional.of(
                RoleTemplate.builder().name("Viewer").isBuiltIn(true)
                        .permissions(EnumSet.of(Permission.PROJECT_VIEW)).build()));

        roleTemplateBootstrapService.ensureBuiltInTemplates();

        verify(roleTemplateRepository).save(dev);
        assertThat(dev.getPermissions()).contains(Permission.SETTINGS_NOTIFICATION_MANAGE);
    }
}
