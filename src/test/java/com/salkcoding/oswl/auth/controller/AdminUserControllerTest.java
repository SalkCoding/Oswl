package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.CreateUserRequest;
import com.salkcoding.oswl.auth.dto.UpdateDisplayNameRequest;
import com.salkcoding.oswl.auth.dto.UpdateUserRolesRequest;
import com.salkcoding.oswl.auth.dto.UserSummaryDto;
import com.salkcoding.oswl.auth.service.UserManagementService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AdminUserController unit tests")
class AdminUserControllerTest {

    @Mock UserManagementService userManagementService;

    @InjectMocks AdminUserController controller;

    @Test
    @DisplayName("list: delegates to userManagementService.findAllUsers()")
    void list_returnsUsers() {
        UserSummaryDto user = mock(UserSummaryDto.class);
        when(userManagementService.findAllUsers()).thenReturn(List.of(user));

        List<UserSummaryDto> result = controller.list();

        assertThat(result).containsExactly(user);
    }

    @Test
    @DisplayName("create: delegates to userManagementService.createUser() and returns dto")
    void create_returnsCreatedUser() {
        CreateUserRequest req = mock(CreateUserRequest.class);
        UserSummaryDto dto = mock(UserSummaryDto.class);
        when(userManagementService.createUser(req)).thenReturn(dto);

        UserSummaryDto result = controller.create(req);

        assertThat(result).isEqualTo(dto);
    }

    @Test
    @DisplayName("updateRoles: delegates to userManagementService.updateUserRoles()")
    void updateRoles_callsService() {
        UpdateUserRolesRequest req = mock(UpdateUserRolesRequest.class);
        when(req.getTemplateIds()).thenReturn(List.of(1L, 2L));

        controller.updateRoles(42L, req);

        verify(userManagementService).updateUserRoles(42L, List.of(1L, 2L));
    }

    @Test
    @DisplayName("updateDisplayName: delegates to userManagementService.updateDisplayName()")
    void updateDisplayName_callsService() {
        UpdateDisplayNameRequest req = mock(UpdateDisplayNameRequest.class);
        when(req.getDisplayName()).thenReturn("New Name");

        controller.updateDisplayName(7L, req);

        verify(userManagementService).updateDisplayName(7L, "New Name");
    }

    @Test
    @DisplayName("activate: calls setUserEnabled(id, true)")
    void activate_enablesUser() {
        controller.activate(5L);

        verify(userManagementService).setUserEnabled(5L, true);
    }

    @Test
    @DisplayName("deactivate: calls setUserEnabled(id, false)")
    void deactivate_disablesUser() {
        controller.deactivate(5L);

        verify(userManagementService).setUserEnabled(5L, false);
    }

    @Test
    @DisplayName("delete: delegates to userManagementService.deleteUser()")
    void delete_callsService() {
        controller.delete(99L);

        verify(userManagementService).deleteUser(99L);
    }
}
