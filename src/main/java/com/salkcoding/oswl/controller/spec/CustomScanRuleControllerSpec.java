package com.salkcoding.oswl.controller.spec;
import com.salkcoding.oswl.dto.scan.CustomRuleSet;
import com.salkcoding.oswl.service.secretscan.CustomScanRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
@Tag(name = "Custom scan rules")
public interface CustomScanRuleControllerSpec {
    @Operation(summary = "Read the deployed custom rule set (system administrator)")
    CustomRuleSet read();
    @Operation(summary = "Validate and atomically deploy rules using the current revision")
    CustomRuleSet publish(CustomRuleSet request);
}
