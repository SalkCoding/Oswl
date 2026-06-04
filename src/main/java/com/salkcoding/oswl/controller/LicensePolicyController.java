package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.LicensePolicyControllerSpec;
import com.salkcoding.oswl.dto.LicensePolicyEntryDto;
import com.salkcoding.oswl.service.LicensePolicyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/settings/license-policy")
@PreAuthorize("hasPermission(null, 'LICENSE_POLICY_MANAGE') or hasRole('SYSTEM_ADMIN')")
@RequiredArgsConstructor
public class LicensePolicyController implements LicensePolicyControllerSpec {

    private final LicensePolicyService licensePolicyService;

    @GetMapping
    public List<LicensePolicyEntryDto> list() {
        return licensePolicyService.findAllEntries();
    }

    @PutMapping("/{spdxId}")
    public ResponseEntity<LicensePolicyEntryDto> update(
            @PathVariable String spdxId,
            @Valid @RequestBody UpdateRequest request) {
        return ResponseEntity.ok(licensePolicyService.updateEntry(spdxId, request.status()));
    }
}
