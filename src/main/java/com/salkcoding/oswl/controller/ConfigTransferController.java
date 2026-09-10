package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.controller.spec.ConfigTransferControllerSpec;
import com.salkcoding.oswl.dto.config.ConfigBundle;
import com.salkcoding.oswl.dto.config.ConfigImportResult;
import com.salkcoding.oswl.service.config.ConfigTransferService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/config-transfer")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SYSTEM_ADMIN')")
public class ConfigTransferController implements ConfigTransferControllerSpec {

    private final ConfigTransferService configTransferService;

    @GetMapping("/export")
    public ConfigBundle export() {
        return configTransferService.export();
    }

    @PostMapping("/import")
    public ConfigImportResult importBundle(@RequestBody ConfigBundle bundle,
                                           @RequestParam(defaultValue = "true") boolean dryRun) {
        return configTransferService.importBundle(bundle, dryRun);
    }
}
