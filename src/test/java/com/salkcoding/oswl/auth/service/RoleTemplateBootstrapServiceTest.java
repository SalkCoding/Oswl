package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.entity.RoleTemplate;
import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.repository.RoleTemplateRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("RoleTemplateBootstrapService 단위 테스트")
class RoleTemplateBootstrapServiceTest {

    @Mock RoleTemplateRepository roleTemplateRepository;

    @InjectMocks RoleTemplateBootstrapService roleTemplateBootstrapService;

    @Test
    @DisplayName("ensureBuiltInTemplates: 템플릿이 없으면 3개를 저장한다")
    void ensureBuiltInTemplates_createsAllThree_whenNoneExist() {
        when(roleTemplateRepository.existsByName("Admin")).thenReturn(false);
        when(roleTemplateRepository.existsByName("Developer")).thenReturn(false);
        when(roleTemplateRepository.existsByName("Viewer")).thenReturn(false);

        roleTemplateBootstrapService.ensureBuiltInTemplates();

        verify(roleTemplateRepository, times(3)).save(any(RoleTemplate.class));
    }

    @Test
    @DisplayName("ensureBuiltInTemplates: 이미 모두 존재하면 저장하지 않는다")
    void ensureBuiltInTemplates_skipsAll_whenAllExist() {
        when(roleTemplateRepository.existsByName("Admin")).thenReturn(true);
        when(roleTemplateRepository.existsByName("Developer")).thenReturn(true);
        when(roleTemplateRepository.existsByName("Viewer")).thenReturn(true);

        roleTemplateBootstrapService.ensureBuiltInTemplates();

        verify(roleTemplateRepository, never()).save(any());
    }

    @Test
    @DisplayName("ensureBuiltInTemplates: Admin만 없으면 1개만 저장한다")
    void ensureBuiltInTemplates_savesOnlyMissing() {
        when(roleTemplateRepository.existsByName("Admin")).thenReturn(false);
        when(roleTemplateRepository.existsByName("Developer")).thenReturn(true);
        when(roleTemplateRepository.existsByName("Viewer")).thenReturn(true);

        roleTemplateBootstrapService.ensureBuiltInTemplates();

        verify(roleTemplateRepository, times(1)).save(argThat(rt ->
                rt.getName().equals("Admin") && rt.isBuiltIn()));
    }

    @Test
    @DisplayName("ensureBuiltInTemplates: Admin 템플릿에는 모든 Permission이 포함된다")
    void ensureBuiltInTemplates_adminHasAllPermissions() {
        when(roleTemplateRepository.existsByName("Admin")).thenReturn(false);
        when(roleTemplateRepository.existsByName("Developer")).thenReturn(true);
        when(roleTemplateRepository.existsByName("Viewer")).thenReturn(true);

        roleTemplateBootstrapService.ensureBuiltInTemplates();

        verify(roleTemplateRepository).save(argThat(rt ->
                rt.getPermissions().containsAll(java.util.EnumSet.allOf(Permission.class))));
    }
}
