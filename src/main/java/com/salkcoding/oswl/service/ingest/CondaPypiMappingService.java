package com.salkcoding.oswl.service.ingest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;

/**
 * Conda package name → PyPI project name, for the subset of conda packages that are just a
 * Python project repackaged for conda (numpy, requests, django, ...). A hit means the package
 * can be queried through OSV's PyPI ecosystem exactly like a real PyPI dependency; a miss means
 * it's very likely a native (non-Python) library — see ROADMAP A9 for why those are left
 * conspicuously {@code UNKNOWN} rather than guessed at.
 *
 * <p>Backed by a bundled snapshot of {@code regro/cf-graph-countyfair}'s
 * {@code mappings/pypi/grayskull_pypi_mapping.json} — the same file conda-forge's own tooling
 * uses to generate PRs, and small enough (~1.8MB) to ship in the jar rather than fetch at
 * runtime. It is a point-in-time snapshot, not a live lookup: a brand-new PyPI repackage won't
 * resolve until this file is refreshed from upstream.
 */
@Slf4j
@Service
public class CondaPypiMappingService {

    private static final String RESOURCE_PATH = "/conda/grayskull-pypi-mapping.json";

    private final Map<String, String> condaNameToPypiName;

    public CondaPypiMappingService() {
        this.condaNameToPypiName = load();
    }

    private static Map<String, String> load() {
        Map<String, String> map = new HashMap<>();
        try (InputStream in = CondaPypiMappingService.class.getResourceAsStream(RESOURCE_PATH)) {
            if (in == null) {
                log.warn("[CondaPypiMapping] Bundled mapping resource {} not found — every conda package will be treated as unmapped (UNKNOWN)", RESOURCE_PATH);
                return Map.of();
            }
            JsonNode root = new ObjectMapper().readTree(in);
            // The outer JSON key is not a reliable name (some entries are keyed by an opaque
            // hash, not conda_name/pypi_name) — always read the fields, never the key.
            for (Iterator<JsonNode> it = root.elements(); it.hasNext(); ) {
                JsonNode entry = it.next();
                String condaName = entry.path("conda_name").asText(null);
                String pypiName = entry.path("pypi_name").asText(null);
                if (condaName != null && !condaName.isBlank() && pypiName != null && !pypiName.isBlank()) {
                    map.putIfAbsent(condaName.toLowerCase(Locale.ROOT), pypiName);
                }
            }
        } catch (Exception e) {
            log.warn("[CondaPypiMapping] Failed to load {}: {}", RESOURCE_PATH, e.getMessage());
            return Map.of();
        }
        log.info("[CondaPypiMapping] Loaded {} conda→PyPI name mappings", map.size());
        return map;
    }

    /** Returns the PyPI project name for a conda package name, or {@code null} if it's not a known Python repackage. */
    public String resolvePypiName(String condaName) {
        if (condaName == null) {
            return null;
        }
        return condaNameToPypiName.get(condaName.toLowerCase(Locale.ROOT));
    }
}
