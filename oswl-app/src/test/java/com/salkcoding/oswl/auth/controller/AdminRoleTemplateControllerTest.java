package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.RoleTemplateDto;
import com.salkcoding.oswl.auth.dto.RoleTemplateRequest;
import com.salkcoding.oswl.auth.enums.Permission;
import com.salkcoding.oswl.auth.service.RoleTemplateService;
import org.springframework.context.MessageSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminRoleTemplateController unit tests")
class AdminRoleTemplateControllerTest {

    @Mock RoleTemplateService roleTemplateService;
    @Mock MessageSource messageSource;

    @InjectMocks AdminRoleTemplateController controller;

    @Test
    @DisplayName("list: delegates to roleTemplateService.findAll()")
    void list_returnsAll() {
        RoleTemplateDto dto = mock(RoleTemplateDto.class);
        when(roleTemplateService.findAll()).thenReturn(List.of(dto));

        List<RoleTemplateDto> result = controller.list();

        assertThat(result).containsExactly(dto);
    }

    @Test
    @DisplayName("allPermissions: returns all Permission enum entries as maps")
    void allPermissions_returnsPermissionList() {
        when(messageSource.getMessage(anyString(), isNull(), anyString(), any()))
                .thenAnswer(inv -> inv.getArgument(2));
        List<Map<String, String>> result = controller.allPermissions(Locale.ENGLISH);

        assertThat(result).hasSizeGreaterThan(0);
        // Each entry has 'code' and 'description'
        for (Map<String, String> entry : result) {
            assertThat(entry).containsKeys("code", "description");
        }
        // All permission codes are present
        for (Permission p : Permission.values()) {
            assertThat(result).anyMatch(m -> p.name().equals(m.get("code")));
        }
    }

    @Test
    @DisplayName("create: delegates to roleTemplateService.create()")
    void create_returnsDto() {
        RoleTemplateRequest req = mock(RoleTemplateRequest.class);
        RoleTemplateDto dto = mock(RoleTemplateDto.class);
        when(roleTemplateService.create(req)).thenReturn(dto);

        RoleTemplateDto result = controller.create(req);

        assertThat(result).isEqualTo(dto);
    }

    @Test
    @DisplayName("update: delegates to roleTemplateService.update()")
    void update_returnsDto() {
        RoleTemplateRequest req = mock(RoleTemplateRequest.class);
        RoleTemplateDto dto = mock(RoleTemplateDto.class);
        when(roleTemplateService.update(10L, req)).thenReturn(dto);

        RoleTemplateDto result = controller.update(10L, req);

        assertThat(result).isEqualTo(dto);
    }

    @Test
    @DisplayName("delete: delegates to roleTemplateService.delete()")
    void delete_callsService() {
        controller.delete(3L);

        verify(roleTemplateService).delete(3L);
    }
}
