package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.AddVcsConnectionRequest;
import com.salkcoding.oswl.auth.dto.VcsConnectionDto;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.VcsConnectionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/settings/vcs")
@RequiredArgsConstructor
@PreAuthorize("hasPermission(null, 'SETTINGS_VCS_MANAGE') or hasRole('SYSTEM_ADMIN')")
public class VcsConnectionController {

    private final VcsConnectionService vcsConnectionService;

    @GetMapping
    public List<VcsConnectionDto> list(@AuthenticationPrincipal OswlUserPrincipal principal) {
        return vcsConnectionService.findByCurrentUser(principal.getUserId());
    }

    @PostMapping
    public VcsConnectionDto add(@AuthenticationPrincipal OswlUserPrincipal principal,
                                @RequestBody @Valid AddVcsConnectionRequest request) {
        return vcsConnectionService.addConnection(principal.getUserId(), request);
    }

    @DeleteMapping("/{id}")
    public void remove(@AuthenticationPrincipal OswlUserPrincipal principal, @PathVariable Long id) {
        vcsConnectionService.removeConnection(id, principal.getUserId());
    }
}
