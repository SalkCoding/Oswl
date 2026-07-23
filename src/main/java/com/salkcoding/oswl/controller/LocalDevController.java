package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.dto.gate.GateResultDto;
import com.salkcoding.oswl.service.ContinuousMonitoringService;
import com.salkcoding.oswl.service.ContinuousMonitoringService.MonitoringSummary;
import com.salkcoding.oswl.service.GatePolicyService;
import com.salkcoding.oswl.service.GatePolicyService.GateOptions;
import com.salkcoding.oswl.service.SbomExportService;
import io.swagger.v3.oas.annotations.Hidden;
import lombok.RequiredArgsConstructor;
import org.cyclonedx.Version;
import org.cyclonedx.parsers.JsonParser;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Local-only developer utilities, served by the unauthenticated local dev filter chain
 * (same as the other /data endpoints).
 *
 * GET /data/monitor                     — run one continuous-monitoring cycle synchronously
 * GET /data/sbom-validate/{projectId}   — export the project SBOM and schema-validate it
 */
@Hidden
@Profile("local")
@RestController
@RequestMapping("/data")
@RequiredArgsConstructor
public class LocalDevController {

    private final ContinuousMonitoringService continuousMonitoringService;
    private final SbomExportService sbomExportService;
    private final GatePolicyService gatePolicyService;

    /** Runs the PR/CI gate policy against a project's latest completed scan (no API key needed). */
    @GetMapping("/gate/{projectId}")
    public GateResultDto gate(@PathVariable Long projectId,
                              @org.springframework.web.bind.annotation.RequestParam(required = false) Boolean onlyNew,
                              @org.springframework.web.bind.annotation.RequestParam(required = false) String failOnSeverity) {
        return gatePolicyService.evaluate(projectId,
                new GateOptions(null, failOnSeverity, null, null, null, onlyNew));
    }

    @GetMapping("/monitor")
    public MonitoringSummary runMonitoringCycle() {
        return continuousMonitoringService.runCycle();
    }

    /** Validates the exported CycloneDX SBOM JSON against the official 1.6 schema. */
    @GetMapping("/sbom-validate/{projectId}")
    public Map<String, Object> validateSbom(@PathVariable Long projectId) throws Exception {
        return validate(sbomExportService.exportUnchecked(projectId, SbomExportService.Format.JSON));
    }

    /** Validates the exported CycloneDX VEX JSON against the official 1.6 schema. */
    @GetMapping("/vex-validate/{projectId}")
    public Map<String, Object> validateVex(@PathVariable Long projectId) throws Exception {
        return validate(sbomExportService.exportVexUnchecked(projectId, SbomExportService.Format.JSON));
    }

    private Map<String, Object> validate(SbomExportService.SbomFile file) throws Exception {
        List<org.cyclonedx.exception.ParseException> errors =
                new JsonParser().validate(file.content().getBytes(StandardCharsets.UTF_8), Version.VERSION_16);
        return Map.of(
                "filename", file.filename(),
                "sizeBytes", file.content().length(),
                "valid", errors.isEmpty(),
                "errors", errors.stream().map(Throwable::getMessage).toList());
    }
}
