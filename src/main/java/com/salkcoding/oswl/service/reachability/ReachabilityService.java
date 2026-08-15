package com.salkcoding.oswl.service.reachability;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.scan.ScanComponent;
import com.salkcoding.oswl.domain.enums.Reachability;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.Set;

/**
 * Orchestrates bytecode reachability analysis for a single scan component.
 *
 * <p>The project bytecode root is configured server-side via
 * {@code oswl.reachability.bytecode-root}. The path is resolved against the
 * project UUID so each project has an isolated subdirectory. When no root is
 * configured or the directory does not exist, analysis returns {@code UNKNOWN}.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReachabilityService {

    private final CallGraphAnalyzer callGraphAnalyzer;

    @Value("${oswl.reachability.bytecode-root:}")
    private String bytecodeRoot;

    /**
     * Determines the reachability of the given component from the project bytecode.
     *
     * @param component the component to analyze
     * @return REACHABLE (with evidence), NOT_REACHABLE, or UNKNOWN
     */
    public CallGraphAnalyzer.AnalysisResult analyze(ScanComponent component) {
        if (component == null || component.getLibrary() == null) {
            return CallGraphAnalyzer.AnalysisResult.of(Reachability.UNKNOWN);
        }

        Set<String> prefixes = LibraryPackageMapper.map(component);
        if (prefixes.isEmpty()) {
            log.debug("[Reachability] No class prefixes for component {} (ecosystem {})",
                    component.getLibrary().getName(), component.getLibrary().getEcosystem());
            return CallGraphAnalyzer.AnalysisResult.of(Reachability.UNKNOWN);
        }

        Path root = resolveBytecodeRoot(component);
        if (root == null) {
            log.debug("[Reachability] No bytecode root configured for component {}",
                    component.getLibrary().getName());
            return CallGraphAnalyzer.AnalysisResult.of(Reachability.UNKNOWN);
        }

        CallGraphAnalyzer.AnalysisResult result = callGraphAnalyzer.analyze(root, prefixes);
        log.info("[Reachability] component={} prefixes={} root={} result={} evidence={}",
                component.getLibrary().getName(), prefixes, root, result.reachability(), result.evidence());
        return result;
    }

    private Path resolveBytecodeRoot(ScanComponent component) {
        if (bytecodeRoot == null || bytecodeRoot.isBlank()) {
            return null;
        }
        Project project = component.getScanResult() != null ? component.getScanResult().getProject() : null;
        if (project == null || project.getProjectUuid() == null) {
            return Path.of(bytecodeRoot.trim());
        }
        return Path.of(bytecodeRoot.trim(), project.getProjectUuid());
    }
}
