package com.salkcoding.oswl.vdb;

import com.fasterxml.jackson.databind.JsonNode;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;

import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

/** Incremental content checks shared by CLI verification and application import. */
public final class BundleDistributionPolicy {
    private final boolean attributed;
    private final boolean delta;
    private final Set<String> findings = new HashSet<>();
    private final Set<String> unresolved = new HashSet<>();

    public BundleDistributionPolicy(String profile, boolean delta) throws IOException {
        if (!Set.of("unreviewed", "github-attributed").contains(profile))
            throw new IOException("Unknown distribution profile");
        this.attributed = profile.equals("github-attributed");
        this.delta = delta;
    }

    public boolean requiresValidation() {
        return attributed;
    }

    public void validateFiles(Set<String> files) throws IOException {
        if (attributed && !Set.of("osv.jsonl", "unresolved.jsonl").containsAll(files))
            throw new IOException("Distribution profile contains unsupported source files");
    }

    public void accept(String file, JsonNode node) throws IOException {
        if (!attributed) return;
        if (node == null || !node.isObject()) throw new IOException("Distribution profile row must be an object");
        if (node.has("_deleted") && !node.path("_deleted").isBoolean())
            throw new IOException("Distribution profile deletion marker must be boolean");
        boolean deleted = node.path("_deleted").asBoolean(false);
        if (file.equals("unresolved.jsonl") && deleted)
            throw new IOException("Distribution profile cannot remove incomplete coverage");
        if (deleted) {
            if (!delta) throw new IOException("Full distribution profile cannot contain deletion records");
            return;
        }
        for (String field : Set.of("ecosystem", "name", "version")) {
            if (!node.path(field).isTextual() || node.path(field).asText().isBlank())
                throw new IOException("Distribution profile requires a component identity");
        }
        String key = AirgappedSnapshotService.componentKey(node.path("ecosystem").asText(),
                node.path("name").asText(), node.path("version").asText());
        if (key == null) throw new IOException("Distribution profile requires a valid component identity");
        if (file.equals("unresolved.jsonl")) {
            unresolved.add(key);
            return;
        }
        findings.add(key);
        if (!node.path("vulns").isArray()) throw new IOException("Distribution profile requires a vulnerability array");
        for (JsonNode vulnerability : node.path("vulns")) {
            JsonNode original = vulnerability.path("osvAdvisory");
            if (OsvOriginalAttribution.githubSource(original) == null
                    || !vulnerability.path("osvId").isTextual()
                    || !vulnerability.path("osvId").asText().equals(original.path("id").asText()))
                throw new IOException("Distribution profile requires matching attributed originals");
        }
    }

    /** Component keys whose partial coverage must still exist after applying the bundle. */
    public Set<String> requiredUnresolvedKeys() {
        return Set.copyOf(findings);
    }

    public void finish() throws IOException {
        if (attributed && !delta && !unresolved.containsAll(findings))
            throw new IOException("Distribution profile requires incomplete coverage for every finding component");
    }
}
