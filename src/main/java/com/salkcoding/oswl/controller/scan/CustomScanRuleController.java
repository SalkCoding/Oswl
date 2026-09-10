package com.salkcoding.oswl.controller.scan;
import com.salkcoding.oswl.dto.scan.CustomRuleSet;
import com.salkcoding.oswl.controller.spec.CustomScanRuleControllerSpec;
import com.salkcoding.oswl.service.secretscan.CustomScanRuleService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/settings/scan-rules")
@RequiredArgsConstructor
public class CustomScanRuleController implements CustomScanRuleControllerSpec {
    private final CustomScanRuleService service;
    @GetMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public CustomRuleSet read() { return service.read(); }
    @PutMapping
    @PreAuthorize("hasRole('SYSTEM_ADMIN')")
    public CustomRuleSet publish(@RequestBody CustomRuleSet request) { return service.publish(request); }
}
