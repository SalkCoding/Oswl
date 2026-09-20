package com.salkcoding.oswl.vdb;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.List;
import static org.assertj.core.api.Assertions.*;

class VdbDistributionProfileTest {
    @ParameterizedTest
    @ValueSource(strings = {"epss", "kev", "depsdev", "osv,epss"})
    void selectedProfileRejectsOtherSourcesBeforeCollection(String sources) {
        assertThatThrownBy(() -> VdbBuildOptions.parse(List.of("--distribution-profile", "github-attributed",
                "--wanted", "wanted.jsonl", "--sources", sources))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void explicitProfileRequiresKnownPolicyAndWantedScope() {
        assertThatThrownBy(() -> VdbBuildOptions.parse(List.of("--distribution-profile", "approved")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VdbBuildOptions.parse(List.of("--distribution-profile", "github-attributed")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(VdbBuildOptions.parse(List.of("--distribution-profile", "github-attributed", "--wanted", "wanted.jsonl")).sources())
                .containsExactly("osv");
        assertThat(VdbBuildOptions.parse(List.of()).distributionProfile()).isEqualTo("unreviewed");
    }
    @ParameterizedTest
    @ValueSource(strings = {"valid", "missing-original", "wrong-id", "foreign-file", "missing-unresolved", "deleted-unresolved"})
    void declaredProfileMustMatchBundleContents(String kind, @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String id = "GHSA-2345-6789-cfgh";
        var original = mapper.readTree("""
                {"id":"%s","modified":"2024-09-01T00:00:00Z","affected":[{"package":{"ecosystem":"npm","name":"fixture"},
                "database_specific":{"source":"https://github.com/github/advisory-database/blob/main/advisories/github-reviewed/2024/09/%s/%s.json"},
                "versions":["1.0.0"]}]}
                """.formatted(id, id, id));
        var vulnerability = mapper.createObjectNode().put("osvId", kind.equals("wrong-id") ? "OTHER-fixture" : id);
        if (!kind.equals("missing-original")) vulnerability.set("osvAdvisory", original);
        var row = mapper.createObjectNode().put("ecosystem", "NPM").put("name", "fixture").put("version", "1.0.0");
        var unknown = row.deepCopy();
        if (kind.equals("deleted-unresolved")) unknown.put("_deleted", true);
        row.putArray("vulns").add(vulnerability);
        var files = new java.util.LinkedHashMap<String, String>();
        files.put("osv.jsonl", row.toString());
        if (!kind.equals("missing-unresolved")) files.put("unresolved.jsonl", unknown.toString());
        if (kind.equals("foreign-file")) files.put("epss.jsonl", "{\"cveId\":\"CVE-2026-1000\",\"score\":0.5}");
        var meta = mapper.createObjectNode().put("formatVersion", 2).put("mode", "full").put("distributionProfile", "github-attributed");
        var manifest = meta.putObject("files");
        for (var entry : files.entrySet()) {
            String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            manifest.putObject(entry.getKey()).put("sha256", hash).put("lines", 1);
        }
        files.put("meta.json", meta.toString());
        var bundle = directory.resolve("bundle.zip");
        try (var zip = new java.util.zip.ZipOutputStream(java.nio.file.Files.newOutputStream(bundle))) {
            for (var entry : files.entrySet()) {
                zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
                zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        if (kind.equals("valid")) {
            assertThatCode(() -> PreviousBundleReader.read(bundle, mapper)).doesNotThrowAnyException();
            assertThat(new VdbBuilderCli().run(new String[] {"verify", bundle.toString()})).isZero();
        } else {
            assertThatThrownBy(() -> PreviousBundleReader.read(bundle, mapper)).isInstanceOf(java.io.IOException.class)
                    .hasMessageContaining("profile");
            assertThat(new VdbBuilderCli().run(new String[] {"verify", bundle.toString()})).isEqualTo(1);
        }
    }

}
