package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.CreateUserRequest;
import com.salkcoding.oswl.auth.dto.UpdateUserRolesRequest;
import com.salkcoding.oswl.auth.dto.UserSummaryDto;
import com.salkcoding.oswl.auth.service.UserManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class AdminUserController {

    private final UserManagementService userManagementService;

    @GetMapping
    public List<UserSummaryDto> list() {
        return userManagementService.findAllUsers();
    }

    @PostMapping
    public UserSummaryDto create(@RequestBody @Valid CreateUserRequest request) {
        return userManagementService.createUser(request);
    }

    @PutMapping("/{id}/roles")
    public void updateRoles(@PathVariable Long id, @RequestBody UpdateUserRolesRequest request) {
        userManagementService.updateUserRoles(id, request.getTemplateIds());
    }

    @PutMapping("/{id}/activate")
    public void activate(@PathVariable Long id) {
        userManagementService.setUserEnabled(id, true);
    }

    @PutMapping("/{id}/deactivate")
    public void deactivate(@PathVariable Long id) {
        userManagementService.setUserEnabled(id, false);
    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {
        userManagementService.deleteUser(id);
    }
}
