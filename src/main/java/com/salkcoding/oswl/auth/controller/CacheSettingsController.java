package com.salkcoding.oswl.auth.controller;

import com.salkcoding.oswl.auth.dto.CacheSettingDto;
import com.salkcoding.oswl.auth.dto.UpdateCacheTtlRequest;
import com.salkcoding.oswl.auth.security.OswlUserPrincipal;
import com.salkcoding.oswl.auth.service.CacheManagementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/settings/cache")
@RequiredArgsConstructor
@PreAuthorize("hasPermission(null, 'SETTINGS_CACHE_MANAGE') or hasRole('SUPER_ADMIN')")
public class CacheSettingsController {

    private final CacheManagementService cacheManagementService;

    @GetMapping
    public List<CacheSettingDto> list() {
        return cacheManagementService.findAll();
    }

    @PutMapping
    public void update(@RequestBody @Valid UpdateCacheTtlRequest request) {
        cacheManagementService.updateTtl(request.getCacheKey(), request.getTtlSeconds());
    }

    @PostMapping("/clear")
    public void clear(@RequestParam String cacheKey,
                      @AuthenticationPrincipal OswlUserPrincipal principal) {
        cacheManagementService.clearCache(cacheKey, principal != null ? principal.getUserId() : null);
    }
}
