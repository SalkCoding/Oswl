package com.salkcoding.oswl.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.domain.enums.RiskLevel;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import com.salkcoding.oswl.service.metrics.OswlMetrics;
import com.salkcoding.oswl.vdb.SimpleVersionComparator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * GitHub Security Advisory GraphQL client.
 *
 * <p>Queries {@code securityVulnerabilities} by package name + ecosystem using a GitHub PAT.
 * Reuses the existing GitHub token infrastructure: the token is supplied at construction time
 * from the deployment configuration (not a per-user VCS connection).
 *
 * <p>Air-gapped mode: queries are answered from the offline snapshot store keyed by component
 * ({@code ECOSYSTEM|name|version}) instead of live GraphQL calls.
 */
@Slf4j
public class GitHubAdvisoryClient {

    private static final String DEFAULT_GRAPHQL_URL = "https://api.github.com/graphql";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(20);

    private static final String QUERY = """
            query($ecosystem: SecurityAdvisoryEcosystem, $package: SecurityAdvisoryPackageName, $first: Int!) {
              securityVulnerabilities(ecosystem: $ecosystem, package: $package, first: $first) {
                pageInfo { hasNextPage }
                nodes {
                  advisory {
                    identifiers { type value }
                    summary
                    cvss { score vectorString }
                  }
                  package { name ecosystem }
                  firstPatchedVersion { identifier }
                  vulnerableVersionRange
                  severity
                }
              }
            }
            """;

    private final RestClient restClient;
    private final String graphqlUrl;
    private final String token;
    private final AirgappedSnapshotService snapshotService;
    private final boolean airgapped;
    private final ObjectMapper objectMapper = new ObjectMapper();
    /** Null until wired by Spring config (unit tests construct the client directly) — every use is guarded. */
    private volatile OswlMetrics oswlMetrics;

    /** Called once by Spring config after construction to enable external-API metrics. */
    public void setOswlMetrics(OswlMetrics oswlMetrics) {
        this.oswlMetrics = oswlMetrics;
    }

    /** Live-HTTP client with no token (will silently return empty results). Used by unit tests. */
    public GitHubAdvisoryClient() {
        this(null, false, null, DEFAULT_GRAPHQL_URL, DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT);
    }

    public GitHubAdvisoryClient(AirgappedSnapshotService snapshotService, boolean airgapped, String token,
                                String apiBase, Duration connectTimeout, Duration readTimeout) {
        this.snapshotService = snapshotService;
        this.airgapped = airgapped && snapshotService != null;
        this.token = (token != null) ? token.strip() : null;
        this.graphqlUrl = resolveGraphqlUrl(apiBase);
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectTimeout);
        factory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder()
                .requestFactory(factory)
                .build();
        if (this.airgapped) {
            log.info("[GitHubAdvisory] Air-gapped mode — advisory lookups served from the offline snapshot store, no outbound HTTP");
        }
    }

    private static String resolveGraphqlUrl(String apiBase) {
        if (apiBase == null || apiBase.isBlank()) {
            return DEFAULT_GRAPHQL_URL;
        }
        String base = apiBase.strip().replaceAll("/+$", "");
        if (base.endsWith("/api/v3")) {
            return base.replace("/api/v3", "/api/graphql");
        }
        if (base.contains("/api/v3")) {
            return base + "/graphql";
        }
        return DEFAULT_GRAPHQL_URL;
    }

    /** One GitHub advisory relevant to a specific package/version. */
    public record GitHubAdvisory(String ghsaId, String cveId, String summary, RiskLevel severity,
                                  Double cvssScore, String cvss3Vector, String fixVersion) {}

    public boolean canLookup(String ecosystem) {
        return !airgapped && token != null && !token.isBlank() && toGitHubEcosystem(ecosystem) != null;
    }

    /**
     * Looks up advisories for a single package/version. Returns an empty list when the ecosystem
     * is not supported by GitHub Advisory or the client is air-gapped. Failed requests throw.
     */
    public List<GitHubAdvisory> findByPackage(String ecosystem, String name, String version) {
        if (airgapped) {
            return List.of();
        }
        String ghEcosystem = toGitHubEcosystem(ecosystem);
        if (ghEcosystem == null) {
            log.debug("[GitHubAdvisory] Skipping {} — ecosystem '{}' is not supported by GitHub Advisory", name, ecosystem);
            return List.of();
        }
        if (token == null || token.isBlank()) {
            log.debug("[GitHubAdvisory] No GitHub token configured — skipping advisory lookup");
            return List.of();
        }
        try {
            List<GitHubAdvisory> result = query(ghEcosystem, name, version);
            recordApiCall(OswlMetrics.OUTCOME_SUCCESS);
            return result;
        } catch (Exception e) {
            recordApiCall(isRateLimited(e) ? OswlMetrics.OUTCOME_RATE_LIMITED : OswlMetrics.OUTCOME_FAILURE);
            log.warn("[GitHubAdvisory] Lookup failed for {}:{} — {}", name, version, e.getMessage());
            throw new IllegalStateException("GitHub Advisory lookup unavailable", e);
        }
    }

    /** External-API call counter — no-op until Spring config wires the metrics bean. */
    private void recordApiCall(String outcome) {
        OswlMetrics m = oswlMetrics;
        if (m != null) {
            m.recordExternalApiCall("github-advisory", outcome);
        }
    }

    /** GitHub rate limiting surfaces as a 403/429 status in the error message. */
    private static boolean isRateLimited(Exception e) {
        String message = e.getMessage();
        return message != null && (message.contains("429") || message.contains("403"));
    }

    /**
     * Offline path: looks up GitHub Advisory-derived CVEs by component key.
     * Returns stored results only; absent keys have no completed lookup evidence.
     */
    public Map<String, List<GitHubAdvisory>> findByComponentKeys(Collection<String> componentKeys) {
        if (!airgapped || componentKeys == null || componentKeys.isEmpty()) {
            return Map.of();
        }
        Map<String, List<AirgappedSnapshotService.SnapshotVuln>> found =
                snapshotService.findGitHubAdvisoryVulns(componentKeys);
        Map<String, List<GitHubAdvisory>> result = new LinkedHashMap<>();
        for (String key : componentKeys) {
            List<AirgappedSnapshotService.SnapshotVuln> vulns = found.get(key);
            if (vulns == null) {
                continue;
            } else {
                result.put(key, vulns.stream()
                        .map(v -> new GitHubAdvisory(v.osvId(), v.cveId(), v.summary(),
                                parseSeverity(v.severity()), v.cvssScore(), v.cvss3Vector(), v.fixVersion()))
                        .toList());
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private List<GitHubAdvisory> query(String ghEcosystem, String name, String version) throws Exception {
        Map<String, Object> variables = Map.of(
                "ecosystem", ghEcosystem,
                "package", name,
                "first", 100);
        Map<String, Object> body = Map.of("query", QUERY, "variables", variables);

        Map<String, Object> response = restClient.post()
                .uri(graphqlUrl)
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null) throw new IllegalStateException("Empty GraphQL response");
        Object errors = response.get("errors");
        if (errors instanceof List<?> errorList && !errorList.isEmpty()) {
            throw new IllegalStateException("GraphQL response contains errors");
        }
        Object data = response.get("data");
        if (!(data instanceof Map<?, ?> dataMap)) throw new IllegalStateException("Missing GraphQL data");
        Object sv = dataMap.get("securityVulnerabilities");
        if (!(sv instanceof Map<?, ?> svMap)) throw new IllegalStateException("Missing vulnerability connection");
        if (svMap.get("pageInfo") instanceof Map<?, ?> pageInfo && Boolean.TRUE.equals(pageInfo.get("hasNextPage")))
            throw new IllegalStateException("Incomplete advisory pagination");
        Object nodes = svMap.get("nodes");
        if (!(nodes instanceof List<?> nodeList)) throw new IllegalStateException("Missing advisory nodes");

        List<GitHubAdvisory> result = new ArrayList<>();
        for (Object nodeObj : nodeList) {
            if (!(nodeObj instanceof Map<?, ?> node) || !(node.get("advisory") instanceof Map<?, ?>))
                throw new IllegalStateException("Malformed advisory node");
            String range = (String) node.get("vulnerableVersionRange");
            if (!isVersionAffected(version, range)) continue;
            result.add(parseAdvisoryNode(node));
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private GitHubAdvisory parseAdvisoryNode(Map<?, ?> node) {
        Map<String, Object> advisory = (Map<String, Object>) node.get("advisory");
        String ghsaId = null;
        String cveId = null;
        Object identifiers = advisory != null ? advisory.get("identifiers") : null;
        if (identifiers instanceof List<?> idList) {
            for (Object idObj : idList) {
                if (!(idObj instanceof Map<?, ?> id)) continue;
                String type = String.valueOf(id.get("type"));
                String value = (String) id.get("value");
                if ("GHSA".equalsIgnoreCase(type) && value != null) ghsaId = value;
                if ("CVE".equalsIgnoreCase(type) && value != null) cveId = value;
            }
        }
        String summary = advisory != null ? (String) advisory.get("summary") : null;
        Double cvssScore = null;
        String cvssVector = null;
        if (advisory != null) {
            Object cvss = advisory.get("cvss");
            if (cvss instanceof Map<?, ?> cvssMap) {
                Object score = cvssMap.get("score");
                if (score instanceof Number n) cvssScore = n.doubleValue();
                cvssVector = (String) cvssMap.get("vectorString");
            }
        }
        RiskLevel severity = parseSeverity((String) node.get("severity"));
        if (severity == RiskLevel.NONE && cvssScore != null) {
            severity = cvssToRiskLevel(cvssScore);
        }
        String fixVersion = null;
        Object fpv = node.get("firstPatchedVersion");
        if (fpv instanceof Map<?, ?> fpvMap) {
            fixVersion = (String) fpvMap.get("identifier");
        }
        return new GitHubAdvisory(ghsaId, cveId, summary, severity, cvssScore, cvssVector, fixVersion);
    }

    private static boolean isVersionAffected(String version, String range) {
        if (range == null || range.isBlank()) return true;
        String normalizedVersion = version == null ? "" : version.strip();
        if (normalizedVersion.isEmpty()) return true;
        String[] parts = range.split(",");
        for (String part : parts) {
            String constraint = part.strip();
            if (constraint.isEmpty()) continue;
            String op;
            String ver;
            if (constraint.startsWith(">=")) { op = ">="; ver = constraint.substring(2).strip(); }
            else if (constraint.startsWith("<=")) { op = "<="; ver = constraint.substring(2).strip(); }
            else if (constraint.startsWith(">")) { op = ">"; ver = constraint.substring(1).strip(); }
            else if (constraint.startsWith("<")) { op = "<"; ver = constraint.substring(1).strip(); }
            else if (constraint.startsWith("=")) { op = "="; ver = constraint.substring(1).strip(); }
            else { op = "="; ver = constraint; }
            try {
                int cmp = SimpleVersionComparator.compare(normalizedVersion, ver);
                boolean ok = switch (op) {
                    case ">=" -> cmp >= 0;
                    case "<=" -> cmp <= 0;
                    case ">" -> cmp > 0;
                    case "<" -> cmp < 0;
                    default -> cmp == 0;
                };
                if (!ok) return false;
            } catch (IllegalArgumentException e) {
                // If we cannot compare, err on the side of surfacing the advisory.
                return true;
            }
        }
        return true;
    }

    private static RiskLevel parseSeverity(String severity) {
        if (severity == null || severity.isBlank()) return RiskLevel.NONE;
        try {
            return RiskLevel.valueOf(severity.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return RiskLevel.NONE;
        }
    }

    private static RiskLevel cvssToRiskLevel(Double score) {
        if (score == null) return RiskLevel.NONE;
        if (score >= 9.0) return RiskLevel.CRITICAL;
        if (score >= 7.0) return RiskLevel.HIGH;
        if (score >= 4.0) return RiskLevel.MEDIUM;
        if (score > 0.0) return RiskLevel.LOW;
        return RiskLevel.NONE;
    }

    private static String toGitHubEcosystem(String ecosystem) {
        if (ecosystem == null) return null;
        return switch (ecosystem.strip().toUpperCase(Locale.ROOT)) {
            case "MAVEN" -> "MAVEN";
            case "NPM" -> "NPM";
            case "PYPI", "PIP" -> "PIP";
            case "GO" -> "GO";
            case "CARGO" -> "RUST";
            case "NUGET" -> "NUGET";
            case "RUBYGEMS" -> "RUBYGEMS";
            case "COMPOSER" -> "COMPOSER";
            default -> null;
        };
    }
}
