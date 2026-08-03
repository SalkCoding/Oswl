package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.client.GitHubAdvisoryClient;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService.SnapshotVuln;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds per-component {@link SnapshotVuln} maps from GitHub Security Advisory GraphQL.
 * Mirrors the live {@link GitHubAdvisoryClient} semantics: one query per package, then
 * version-range filtering so only advisories affecting the wanted version are kept.
 */
final class GitHubAdvisorySource {

    private final ObjectMapper mapper;

    GitHubAdvisorySource(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    record Result(Map<String, List<SnapshotVuln>> vulnsByComponentKey) {}

    Result fetch(List<WantedComponent> wanted, String token, String apiBase) {
        if (token == null || token.isBlank()) {
            System.err.println("[oswl-vdb] github-advisory: no token configured — skipping");
            return new Result(Map.of());
        }
        GitHubAdvisoryClient client = new GitHubAdvisoryClient(
                null, false, token, apiBase, Duration.ofSeconds(5), Duration.ofSeconds(20));
        Map<String, List<SnapshotVuln>> result = new LinkedHashMap<>();
        int done = 0;
        for (WantedComponent w : wanted) {
            done++;
            if (done % 50 == 0) {
                System.err.println("[oswl-vdb] github-advisory: " + done + "/" + wanted.size());
            }
            String key = AirgappedSnapshotService.componentKey(w.ecosystem(), w.name(), w.version());
            if (key == null) continue;
            try {
                List<GitHubAdvisoryClient.GitHubAdvisory> advisories =
                        client.findByPackage(w.ecosystem(), w.name(), w.version());
                if (advisories.isEmpty()) continue;
                List<SnapshotVuln> vulns = new ArrayList<>(advisories.size());
                for (GitHubAdvisoryClient.GitHubAdvisory adv : advisories) {
                    vulns.add(toSnapshotVuln(adv));
                }
                result.put(key, vulns);
            } catch (Exception e) {
                System.err.println("[oswl-vdb] github-advisory lookup failed for " + w.name() + "@" + w.version()
                        + ": " + e.getMessage());
            }
        }
        System.err.println("[oswl-vdb] github-advisory: " + result.size() + " component(s) with advisories");
        return new Result(result);
    }

    private static SnapshotVuln toSnapshotVuln(GitHubAdvisoryClient.GitHubAdvisory adv) {
        return new SnapshotVuln(
                adv.ghsaId(),
                adv.cveId(),
                adv.summary(),
                adv.fixVersion(),
                null, // GitHub Advisory does not expose CWE in this GraphQL fragment
                severityName(adv.severity()),
                adv.cvssScore(),
                adv.cvss3Vector(),
                null // GitHub matches are exact package matches, not CPE inference
        );
    }

    private static String severityName(RiskLevel severity) {
        return severity != null && severity != RiskLevel.NONE ? severity.name() : null;
    }
}
