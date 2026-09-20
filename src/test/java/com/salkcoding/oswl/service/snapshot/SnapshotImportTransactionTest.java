package com.salkcoding.oswl.service.snapshot;

import com.salkcoding.oswl.domain.entity.snapshot.SnapshotEntry;
import com.salkcoding.oswl.exception.InvalidRequestException;
import com.salkcoding.oswl.repository.snapshot.SnapshotEntryRepository;
import com.salkcoding.oswl.repository.snapshot.SnapshotMetaRepository;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;

import static org.assertj.core.api.Assertions.*;

@org.springframework.test.annotation.DirtiesContext(classMode = org.springframework.test.annotation.DirtiesContext.ClassMode.AFTER_CLASS)
@SpringBootTest(properties = {
        "spring.datasource.url=${OSWL_SNAPSHOT_IMPORT_TEST_URL:jdbc:h2:mem:snapshot-budget;DB_CLOSE_DELAY=0;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS TEXT}",
        "spring.datasource.driver-class-name=${OSWL_SNAPSHOT_IMPORT_TEST_DRIVER:org.h2.Driver}",
        "spring.datasource.username=${OSWL_SNAPSHOT_IMPORT_TEST_USER:sa}",
        "spring.datasource.password=${OSWL_SNAPSHOT_IMPORT_TEST_PASSWORD:}",
        "spring.jpa.database-platform=${OSWL_SNAPSHOT_IMPORT_TEST_DIALECT:org.hibernate.dialect.H2Dialect}"})
class SnapshotImportTransactionTest {

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"online,0", "offline,0", "offline,8", "offline,999"})
    void importedSeverityCorrectionMatchesOnlineWithoutTrustingStaleCoverage(String mode, int age) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String name = "severity-parity-" + UUID.randomUUID();
        var library = libraries.save(com.salkcoding.oswl.domain.entity.vulnerability.Library.builder()
                .name(name).version("1.0.0").ecosystem("NPM").build());
        var stored = com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder().library(library).ghsaId("OSV-severity")
                .sources(new HashSet<>(Set.of(com.salkcoding.oswl.domain.enums.CveSource.OSV))).build();
        try {
            for (int revision = 1; revision <= 2; revision++) {
                String vector = revision == 1 ? "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H"
                        : "CVSS:3.1/AV:L/AC:H/PR:H/UI:R/S:U/C:L/I:N/A:N";
                var original = json.valueToTree(Map.of("id", "OSV-severity", "modified", "2026-0" + revision + "-01T00:00:00Z",
                        "severity", List.of(Map.of("type", "CVSS_V3", "score", vector)),
                        "affected", List.of(Map.of("package", Map.of("ecosystem", "npm", "name", name),
                                "ranges", List.of(Map.of("type", "SEMVER", "events", List.of(
                                        Map.of("introduced", "0"), Map.of("fixed", "3.0.0"))))))));
                String line = json.writeValueAsString(Map.of("ecosystem", "npm", "name", name, "version", "1.0.0",
                        "vulns", List.of(Map.of("osvId", "OSV-severity", "osvAdvisory", original))));
                String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(line.getBytes(StandardCharsets.UTF_8)));
                int sourceAge = revision == 1 ? 0 : age;
                Map<String, String> date = sourceAge == 999 ? Map.of() : Map.of("asOf", java.time.LocalDate.now().minusDays(sourceAge).toString());
                String meta = json.writeValueAsString(Map.of("formatVersion", 2, "sources", Map.of("osv", date),
                        "files", Map.of("osv.jsonl", Map.of("sha256", hash, "lines", 1))));
                service.importBundle(new ByteArrayInputStream(bundle(Map.of("osv.jsonl", line, "meta.json", meta))));
                var query = new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", name, "1.0.0");
                var offline = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(query)).getFirst();
                var builder = org.springframework.web.client.RestClient.builder().baseUrl("https://api.osv.dev");
                var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
                server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api.osv.dev/v1/querybatch"))
                        .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                                json.writeValueAsString(Map.of("results", List.of(Map.of("vulns", List.of(original))))), org.springframework.http.MediaType.APPLICATION_JSON));
                server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api.osv.dev/v1/vulns/OSV-severity"))
                        .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(original.toString(), org.springframework.http.MediaType.APPLICATION_JSON));
                var onlineClient = new com.salkcoding.oswl.client.OsvClient();
                org.springframework.test.util.ReflectionTestUtils.setField(onlineClient, "restClient", builder.build());
                var online = onlineClient.queryBatch(List.of(query)).getFirst();
                server.verify();
                assertThat(online.vulns()).hasSize(1);
                var currentFinding = online.vulns().getFirst();
                // An outdated or undated bundle retains findings but cannot confirm a fix.
                var expectedOfflineFinding = sourceAge == 0 ? currentFinding
                        : new com.salkcoding.oswl.client.OsvClient.OsvVuln(currentFinding.osvId(), currentFinding.cveId(),
                                currentFinding.summary(), null, currentFinding.cweId(), currentFinding.severity(),
                                currentFinding.cvssScore(), currentFinding.cvssVector(), currentFinding.fixVersionConflictCandidates());
                assertThat(offline.vulns()).containsExactly(expectedOfflineFinding);
                assertThat(offline.advisoryRevisions()).isEqualTo(online.advisoryRevisions());
                assertThat(offline.advisoryDigests()).isEqualTo(online.advisoryDigests());
                assertThat(online.resolved()).isTrue();
                assertThat(offline.resolved()).isEqualTo(sourceAge == 0);
                assertThat(online.commonFix().version()).isEqualTo("3.0.0");
                assertThat(offline.commonFix().version()).isEqualTo(sourceAge == 0 ? "3.0.0" : null);
                var selected = mode.equals("online") ? online : offline;
                var finding = selected.vulns().getFirst();
                stored.mergeSeverity(com.salkcoding.oswl.domain.enums.CveSource.OSV, finding.severity());
                com.salkcoding.oswl.service.vulnerability.OsvStoredEvidence.record(stored, selected, finding, selected.resolved());
                cves.save(stored);
                stored = cves.findById(stored.getId()).orElseThrow();
                boolean corrected = revision == 2 && (mode.equals("online") || sourceAge == 0);
                assertThat(stored.getSeverity()).isEqualTo(corrected ? com.salkcoding.oswl.domain.enums.RiskLevel.LOW
                        : com.salkcoding.oswl.domain.enums.RiskLevel.CRITICAL);
                assertThat(stored.getCvss3Vector()).isEqualTo(corrected ? vector : "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H");
            }
        } finally {
            libraries.deleteById(library.getId());
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,active", "true,active", "false,reactivated", "true,reactivated",
            "false,withdrawn", "true,withdrawn", "false,unaffected", "true,unaffected"})
    void newestOsvRevisionInImportedBundleMatchesCurrentOnlineResult(boolean reverse, String state) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var older = json.readTree("""
                {"id":"OSV-revision-fixture","modified":"2026-01-01T00:00:00Z","affected":[{
                "package":{"ecosystem":"npm","name":"revision-fixture"},
                "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"2.0.0"}]}]}]}
                """);
        var newer = older.deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) newer).put("modified", "2026-02-01T00:00:00Z");
        ((com.fasterxml.jackson.databind.node.ObjectNode) newer.at("/affected/0/ranges/0/events/1"))
                .put("fixed", state.equals("unaffected") ? "0.5.0" : "3.0.0");
        if (state.equals("reactivated")) ((com.fasterxml.jackson.databind.node.ObjectNode) older).put("withdrawn", "2026-01-01T00:00:00Z");
        if (state.equals("withdrawn")) ((com.fasterxml.jackson.databind.node.ObjectNode) newer).put("withdrawn", "2026-02-01T00:00:00Z");
        var originals = reverse ? List.of(newer, older) : List.of(older, newer);
        var rows = originals.stream().map(raw -> Map.of("osvId", "OSV-revision-fixture", "osvAdvisory", raw)).toList();
        String line = json.writeValueAsString(Map.of("ecosystem", "npm", "name", "revision-fixture", "version", "1.0.0", "vulns", rows));
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(line.getBytes(StandardCharsets.UTF_8)));
        String meta = json.writeValueAsString(Map.of("formatVersion", 2, "sources", Map.of("osv", Map.of("asOf", java.time.LocalDate.now().toString())),
                "files", Map.of("osv.jsonl", Map.of("sha256", hash, "lines", 1))));
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("osv.jsonl", line, "meta.json", meta))));
        var query = new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "revision-fixture", "1.0.0");
        var offline = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(query)).getFirst();
        var builder = org.springframework.web.client.RestClient.builder().baseUrl("https://api.osv.dev");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api.osv.dev/v1/querybatch"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        json.writeValueAsString(Map.of("results", List.of(Map.of("vulns", List.of(newer))))), org.springframework.http.MediaType.APPLICATION_JSON));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api.osv.dev/v1/vulns/OSV-revision-fixture"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(newer.toString(), org.springframework.http.MediaType.APPLICATION_JSON));
        var onlineClient = new com.salkcoding.oswl.client.OsvClient();
        org.springframework.test.util.ReflectionTestUtils.setField(onlineClient, "restClient", builder.build());
        var online = onlineClient.queryBatch(List.of(query)).getFirst();
        assertThat(offline.resolved()).isTrue();
        assertThat(offline.vulns()).isEqualTo(online.vulns());
        assertThat(offline.commonFix()).isEqualTo(online.commonFix());
        assertThat(offline.advisoryRevisions()).isEqualTo(online.advisoryRevisions());
        assertThat(offline.advisoryDigests()).isEqualTo(online.advisoryDigests());
        assertThat(offline.lifecycleObservations()).hasSize(2);
        String key = AirgappedSnapshotService.componentKey("npm", "revision-fixture", "1.0.0");
        assertThat(service.findOsvVulns(List.of(key)).get(key)).extracting(AirgappedSnapshotService.SnapshotVuln::osvAdvisory)
                .containsExactlyElementsOf(originals);
        server.verify();
    }

    @Test
    void exportedEpssAttributionSurvivesImportWithoutClaimingRights() throws Exception {
        var library = libraries.saveAndFlush(com.salkcoding.oswl.domain.entity.vulnerability.Library.builder()
                .name("epss-notice-" + UUID.randomUUID()).version("1.0.0").ecosystem("NPM").build());
        cves.saveAndFlush(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder().library(library)
                .cveId("CVE-2026-123450").epssScore(0.4)
                .sources(Set.of(com.salkcoding.oswl.domain.enums.CveSource.OSV)).build());
        byte[] bytes = service.exportBundle();
        var notices = exportedMeta(bytes).path("dataNotices");
        assertThat(notices.has("epss")).isTrue();
        {
            var epss = notices.path("epss");
            assertThat(epss.path("attribution").asText()).contains("FIRST", "Empirical Security");
            assertThat(epss.path("sourceUrl").asText()).isEqualTo("https://www.first.org/epss/");
            assertThat(epss.path("termsUrl").asText()).isEqualTo("https://www.first.org/about/policies/terms");
            assertThat(epss.path("rightsStatus").asText()).isEqualTo("unreviewed");
            assertThat(epss.has("license")).isFalse();
            service.importBundle(new ByteArrayInputStream(bytes));
            var inherited = exportedMeta(service.exportBundle()).path("upstreamDataNotices");
            assertThat(inherited.findValues("epss")).contains(epss);
        }
        assertThat(exportedMeta(service.exportBundle("github-attributed")).path("dataNotices").has("epss")).isFalse();
    }

    @Test
    void exportPreservesHighlyCompressibleEvidenceAcrossImport() throws Exception {
        var library = libraries.saveAndFlush(com.salkcoding.oswl.domain.entity.vulnerability.Library.builder()
                .name("compressible-export").version("1.0.0").ecosystem("NPM").build());
        cves.saveAndFlush(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder().library(library)
                .ghsaId("GHSA-2345-6789-cfgh").summary("repeated advisory text ".repeat(10000))
                .sources(Set.of(com.salkcoding.oswl.domain.enums.CveSource.OSV)).build());
        byte[] bundle = service.exportBundle();
        service.importBundle(new ByteArrayInputStream(bundle));
        assertThat(service.findOsvVulns(List.of("NPM|compressible-export|1.0.0"))
                .get("NPM|compressible-export|1.0.0").getFirst().summary())
                .isEqualTo("repeated advisory text ".repeat(10000));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void attributedExportFiltersOtherSourcesAndKeepsPartialCoverage(boolean changedEvidence) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String name = "restricted-export-" + UUID.randomUUID();
        String id = "GHSA-2345-6789-cfgh";
        String source = "https://github.com/github/advisory-database/blob/main/advisories/github-reviewed/2024/09/" + id + "/" + id + ".json";
        var original = json.readTree("""
                {"id":"%s","modified":"2024-09-01T00:00:00Z","credits":[{"name":"fixture author"}],
                "affected":[{"package":{"ecosystem":"npm","name":"%s"},"database_specific":{"source":"%s"},
                "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"2.0.0"}]}]}]}
                """.formatted(id, name, source));
        var foreign = json.readTree(original.toString().replace(id, "OTHER-fixture"));
        String key = "NPM|" + name + "|1.0.0";
        String payload = json.writeValueAsString(List.of(Map.of("osvId", id, "osvAdvisory", original),
                Map.of("osvId", "OTHER-fixture", "osvAdvisory", foreign)));
        entries.saveAndFlush(SnapshotEntry.builder().source("osv").entryKey(key).payload(payload).build());
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("osv")
                .recordCount(1).importedAt(java.time.LocalDateTime.now()).sourceAsOf(java.time.LocalDate.now()).build());
        var query = new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", name, "1.0.0");
        var assessment = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(query)).getFirst();
        assertThat(assessment.resolved()).isTrue();
        var library = com.salkcoding.oswl.domain.entity.vulnerability.Library.builder().name(name).version("1.0.0").ecosystem("NPM").build();
        library.recordLookupOutcomes(Map.of("OSV", "RESOLVED"));
        library.recordOsvFixAssessment("2.0.0", "SOURCE_FIXED_EVENT", assessment.advisoryRevisions(), Set.of(id, "OTHER-fixture"),
                java.time.Instant.now().plusSeconds(3600), assessment.advisoryDigests());
        library = libraries.saveAndFlush(library);
        cves.saveAndFlush(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder().library(library).ghsaId(id)
                .cveId("CVE-2026-123450").summary("combined-source-text-must-not-leak").epssScore(.9).kevListed(true)
                .sources(Set.of(com.salkcoding.oswl.domain.enums.CveSource.OSV, com.salkcoding.oswl.domain.enums.CveSource.NVD)).build());
        if (changedEvidence) {
            entries.deleteAll();
            entries.saveAndFlush(SnapshotEntry.builder().source("osv").entryKey(key)
                    .payload(payload.replace("fixture author", "changed author")).build());
        }
        byte[] bytes = service.exportBundle("github-attributed");
        Map<String, String> files = new LinkedHashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry file;
            while ((file = zip.getNextEntry()) != null) files.put(file.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(files.keySet()).containsExactlyInAnyOrder("meta.json", "osv.jsonl", "unresolved.jsonl");
        assertThat(files.get("osv.jsonl")).doesNotContain("OTHER-fixture", "combined-source-text-must-not-leak");
        assertThat(exportedMeta(bytes).path("distributionProfile").asText()).isEqualTo("github-attributed");
        if (changedEvidence) assertThat(files.get("osv.jsonl")).isEmpty();
        else assertThat(json.readTree(files.get("osv.jsonl").strip()).path("vulns").get(0).path("osvAdvisory")).isEqualTo(original);
        service.importBundle(new ByteArrayInputStream(bytes));
        assertThat(service.findUnresolvedKeys(List.of(key))).containsExactly(key);
        var result = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(query)).getFirst();
        assertThat(result.resolved()).isFalse();
        assertThat(result.commonFix().version()).isNull();
        assertThat(result.vulns()).hasSize(changedEvidence ? 0 : 1);
        assertThatThrownBy(() -> service.exportBundle("approved")).isInstanceOf(InvalidRequestException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void exportKeepsStoredUncertaintyDespiteResolvedLibraryFlag(boolean unresolved, boolean alias) throws Exception {
        String name = "export-coverage-" + UUID.randomUUID();
        String queryName = alias ? "https://github.com/fixture/" + name : name;
        String queryEcosystem = alias ? "SwiftURL" : "npm";
        String key = AirgappedSnapshotService.componentKey(queryEcosystem, queryName, "1.0.0");
        var library = com.salkcoding.oswl.domain.entity.vulnerability.Library.builder()
                .name(name).version("1.0.0").ecosystem(alias ? "COCOAPODS" : "NPM").sourceRepoUrl(alias ? queryName : null).build();
        library.recordLookupOutcomes(Map.of("OSV", "RESOLVED"));
        library.markFetched();
        libraries.saveAndFlush(library);
        if (unresolved) entries.saveAndFlush(SnapshotEntry.builder().source("unresolved").entryKey(key)
                .payload(new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(
                        Map.of("ecosystem", queryEcosystem, "name", queryName, "version", "1.0.0"))).build());
        byte[] exported = service.exportBundle();
        Map<String, String> files = new HashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(exported))) {
            ZipEntry file;
            while ((file = zip.getNextEntry()) != null)
                files.put(file.getName(), new String(zip.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertThat(files.get("unresolved.jsonl").contains(name)).isEqualTo(unresolved);
        assertThat(files.get("osv.jsonl")).contains(name);
        service.importBundle(new ByteArrayInputStream(exported), AirgappedSnapshotService.ImportMode.REPLACE);
        assertThat(service.findUnresolvedKeys(List.of(key)).contains(key)).isEqualTo(unresolved);
        if (unresolved) {
            var result = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(
                    new com.salkcoding.oswl.client.OsvClient.OsvQuery(queryEcosystem, queryName, "1.0.0"))).getFirst();
            assertThat(result.resolved()).isFalse();
            assertThat(result.commonFix().version()).isNull();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"empty", "other", "keep", "remove-osv"})
    void replacingCoverageCannotResolveRetainedAttributedData(String replacement) throws Exception {
        String row = "{\"ecosystem\":\"npm\",\"name\":\"fixture\",\"version\":\"1.0.0\"}";
        String meta = "{\"distributionProfile\":\"github-attributed\",\"sources\":{\"osv\":{\"asOf\":\""
                + java.time.LocalDate.now() + "\"}}}";
        service.importBundle(new ByteArrayInputStream(versionedBundle(Map.of("meta.json", meta,
                "osv.jsonl", row.substring(0, row.length() - 1) + ",\"vulns\":[]}", "unresolved.jsonl", row), "base", null)));
        Map<String, String> files = new LinkedHashMap<>();
        files.put("unresolved.jsonl", replacement.equals("keep") ? row : replacement.equals("other") ? row.replace("fixture", "other") : "");
        if (replacement.equals("remove-osv")) files.put("osv.jsonl", "");
        byte[] archive = versionedBundle(files, "next", null);
        if (replacement.equals("empty") || replacement.equals("other")) {
            assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(archive), AirgappedSnapshotService.ImportMode.REPLACE))
                    .isInstanceOf(InvalidRequestException.class).hasMessageContaining("profile");
            assertThat(metadata.findById("unresolved").orElseThrow().getBundleId()).isEqualTo("base");
            assertThat(service.findUnresolvedKeys(List.of("NPM|fixture|1.0.0"))).containsExactly("NPM|fixture|1.0.0");
        } else {
            service.importBundle(new ByteArrayInputStream(archive), AirgappedSnapshotService.ImportMode.REPLACE);
            assertThat(entries.countBySource("osv")).isEqualTo(replacement.equals("remove-osv") ? 0 : 1);
        }
        var result = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(
                new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "fixture", "1.0.0"))).getFirst();
        assertThat(result.resolved()).isFalse();
        assertThat(result.commonFix().version()).isNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "unreviewed,github-attributed,MERGE,false", "github-attributed,unreviewed,MERGE,false",
            "github-attributed,github-attributed,MERGE,true", "unreviewed,unreviewed,MERGE,true",
            "unreviewed,github-attributed,REPLACE,true", "github-attributed,unreviewed,REPLACE,true"})
    void distributionProfilesCannotBeMixedByDelta(String original, String incoming,
            AirgappedSnapshotService.ImportMode mode, boolean accepted) throws Exception {
        String row = "{\"ecosystem\":\"npm\",\"name\":\"fixture\",\"version\":\"1.0.0\"}";
        var files = new LinkedHashMap<String, String>();
        files.put("osv.jsonl", row.substring(0, row.length() - 1) + ",\"vulns\":[]}");
        files.put("unresolved.jsonl", row);
        files.put("meta.json", "{\"distributionProfile\":\"" + original + "\"}");
        service.importBundle(new ByteArrayInputStream(versionedBundle(files, "base", null)), AirgappedSnapshotService.ImportMode.REPLACE);
        files.put("meta.json", "{\"distributionProfile\":\"" + incoming + "\"}");
        byte[] next = versionedBundle(files, "next", mode == AirgappedSnapshotService.ImportMode.MERGE ? "base" : null);
        if (accepted) {
            service.importBundle(new ByteArrayInputStream(next), mode);
            assertThat(metadata.findById("osv").orElseThrow().getBundleId()).isEqualTo("next");
        } else {
            assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(next), mode))
                    .isInstanceOf(InvalidRequestException.class).hasMessageContaining("profile");
            assertThat(metadata.findById("osv").orElseThrow().getBundleId()).isEqualTo("base");
        }
        assertThat(metadata.findById("osv").orElseThrow().getDistributionProfile()).isEqualTo(accepted ? incoming : original);
        assertThat(service.findUnresolvedKeys(List.of("NPM|fixture|1.0.0"))).containsExactly("NPM|fixture|1.0.0");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {
            "{\"mode\":\"delta\"}",
            "{\"mode\":\"delta\",\"bundleId\":\"next\"}",
            "{\"formatVersion\":1,\"mode\":\"delta\"}",
            "{\"formatVersion\":2,\"mode\":\"delta\",\"bundleId\":\"next\"}"})
    void deltaCannotOmitItsVersionedBaseline(String meta) throws Exception {
        String row = "{\"cveId\":\"CVE-2026-9000\",\"score\":0.9}";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var root = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(meta);
        if (root.path("formatVersion").asInt() == 2) {
            var entry = root.putObject("files").putObject("epss.jsonl");
            entry.put("sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(row.getBytes(StandardCharsets.UTF_8))));
            entry.put("lines", 1);
        }
        byte[] archive = bundle(Map.of("meta.json", mapper.writeValueAsString(root), "epss.jsonl", row));
        for (var mode : AirgappedSnapshotService.ImportMode.values()) {
            assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(archive), mode))
                    .isInstanceOf(InvalidRequestException.class).hasMessageContaining("delta");
            assertOldSource();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"MERGE,false,false", "MERGE,true,true", "REPLACE,false,true"})
    void unbasedMergeCannotClaimAnExactBundleBaseline(AirgappedSnapshotService.ImportMode mode,
                                                     boolean initiallyEmpty, boolean exact) throws Exception {
        if (initiallyEmpty) entries.deleteAll();
        String row = "{\"cveId\":\"CVE-2026-9001\",\"score\":0.8}";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(row.getBytes(StandardCharsets.UTF_8)));
        String manifest = "\"files\":{\"epss.jsonl\":{\"sha256\":\"" + hash + "\",\"lines\":1}}";
        String full = "{\"formatVersion\":2,\"mode\":\"full\",\"bundleId\":\"base\"," + manifest + "}";
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("meta.json", full, "epss.jsonl", row))), mode);
        assertThat(metadata.findById("epss").orElseThrow().getBundleId()).isEqualTo(exact ? "base" : null);
        assertThat(service.findEpssScores(List.of("CVE-2026-9001"))).containsEntry("CVE-2026-9001", .8);
        String delta = "{\"formatVersion\":2,\"mode\":\"delta\",\"bundleId\":\"next\",\"basedOnBundleId\":\"base\"," + manifest + "}";
        byte[] bytes = bundle(Map.of("meta.json", delta, "epss.jsonl", row));
        if (exact) {
            service.importBundle(new ByteArrayInputStream(bytes), AirgappedSnapshotService.ImportMode.MERGE);
            assertThat(metadata.findById("epss").orElseThrow().getBundleId()).isEqualTo("next");
        } else {
            assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bytes), AirgappedSnapshotService.ImportMode.MERGE))
                    .isInstanceOf(InvalidRequestException.class).hasMessageContaining("base");
            assertThat(service.findEpssScores(List.of("CVE-2026-9000"))).containsEntry("CVE-2026-9000", .25);
            assertThat(metadata.findById("epss").orElseThrow().getBundleId()).isNull();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"base,MERGE,true", "other,MERGE,false", "missing,MERGE,false", "base,REPLACE,false"})
    void declaredDeltaBaseMustMatchStoredSource(String storedId, AirgappedSnapshotService.ImportMode mode,
                                               boolean accepted) throws Exception {
        if (!storedId.equals("missing")) metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder()
                .source("epss").recordCount(1).importedAt(java.time.LocalDateTime.now()).bundleId(storedId).build());
        String row = "{\"cveId\":\"CVE-2026-9000\",\"score\":0.8}";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(row.getBytes(StandardCharsets.UTF_8)));
        String meta = "{\"formatVersion\":2,\"mode\":\"delta\",\"bundleId\":\"next\",\"basedOnBundleId\":\"base\","
                + "\"files\":{\"epss.jsonl\":{\"sha256\":\"" + hash + "\",\"lines\":1}}}";
        byte[] archive = bundle(Map.of("meta.json", meta, "epss.jsonl", row));
        if (accepted) {
            service.importBundle(new ByteArrayInputStream(archive), mode);
            assertThat(service.findEpssScores(List.of("CVE-2026-9000"))).containsEntry("CVE-2026-9000", .8);
            assertThat(metadata.findById("epss").orElseThrow().getBundleId()).isEqualTo("next");
            assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(archive), mode))
                    .isInstanceOf(InvalidRequestException.class).hasMessageContaining("base");
            assertThat(service.findEpssScores(List.of("CVE-2026-9000"))).containsEntry("CVE-2026-9000", .8);
        } else {
            assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(archive), mode))
                    .isInstanceOf(InvalidRequestException.class).hasMessageContaining("base");
            assertThat(service.findEpssScores(List.of("CVE-2026-9000"))).containsEntry("CVE-2026-9000", .25);
            assertThat(metadata.findById("epss").map(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta::getBundleId))
                    .isEqualTo(storedId.equals("missing") ? Optional.empty() : Optional.of(storedId));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"unknown-profile", "foreign-file", "missing-original", "missing-unresolved"})
    void rejectedDistributionProfileCannotMutateStoredData(String kind) throws Exception {
        String profile = kind.equals("unknown-profile") ? "approved" : "github-attributed";
        Map<String, String> files = new LinkedHashMap<>();
        files.put("meta.json", "{\"distributionProfile\":\"" + profile + "\"}");
        if (kind.equals("foreign-file") || kind.equals("unknown-profile")) {
            files.put("epss.jsonl", "{\"cveId\":\"CVE-2026-1000\",\"score\":0.5}");
        } else {
            String row = "{\"ecosystem\":\"npm\",\"name\":\"fixture\",\"version\":\"1.0.0\"";
            files.put("osv.jsonl", row + ",\"vulns\":[{\"osvId\":\"GHSA-2345-6789-cfgh\"}]}");
            if (kind.equals("missing-unresolved")) {
                String id = "GHSA-2345-6789-cfgh";
                String source = "https://github.com/github/advisory-database/blob/main/advisories/github-reviewed/2024/09/" + id + "/" + id + ".json";
                var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
                var record = mapper.readTree(files.get("osv.jsonl"));
                ((com.fasterxml.jackson.databind.node.ObjectNode) record.path("vulns").get(0)).set("osvAdvisory", mapper.readTree("""
                        {"id":"%s","modified":"2024-09-01T00:00:00Z","affected":[{"package":{"ecosystem":"npm","name":"fixture"},
                        "database_specific":{"source":"%s"},"ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"2.0.0"}]}]}]}
                        """.formatted(id, source)));
                files.put("osv.jsonl", mapper.writeValueAsString(record));
            } else files.put("unresolved.jsonl", row + "}");
        }
        for (var mode : AirgappedSnapshotService.ImportMode.values()) {
            assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(files)), mode))
                    .isInstanceOf(InvalidRequestException.class).hasMessageContaining("profile");
            assertOldSource();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({
            "false,false,MERGE,false,false", "false,false,REPLACE,false,false",
            "true,false,MERGE,true,false", "false,true,MERGE,true,false", "true,true,REPLACE,false,false",
            "true,false,REPLACE,false,true", "true,false,MERGE,true,true"})
    void attributedDeltaRequiresEffectiveUnresolvedCoverage(boolean stored, boolean incoming,
            AirgappedSnapshotService.ImportMode mode, boolean accepted, boolean emptyCoverageFile) throws Exception {
        String row = "{\"ecosystem\":\"npm\",\"name\":\"fixture\",\"version\":\"1.0.0\"}";
        String key = "NPM|fixture|1.0.0";
        if (stored) entries.saveAndFlush(SnapshotEntry.builder().source("unresolved").entryKey(key).payload(row).build());
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder()
                .source("osv").recordCount(0).importedAt(java.time.LocalDateTime.now()).bundleId("base").distributionProfile("github-attributed").build());
        if (stored) metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder()
                .source("unresolved").recordCount(1).importedAt(java.time.LocalDateTime.now()).bundleId("base").distributionProfile("github-attributed").build());
        Map<String, String> files = new LinkedHashMap<>();
        files.put("meta.json", "{\"distributionProfile\":\"github-attributed\",\"mode\":\"delta\"}");
        files.put("osv.jsonl", row.substring(0, row.length() - 1) + ",\"vulns\":[]}");
        if (incoming) files.put("unresolved.jsonl", row);
        if (emptyCoverageFile) files.put("unresolved.jsonl", "");
        byte[] archive = versionedBundle(files, "next", "base");
        if (accepted) {
            service.importBundle(new ByteArrayInputStream(archive), mode);
            assertThat(service.findUnresolvedKeys(List.of(key))).containsExactly(key);
            var result = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(
                    new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "fixture", "1.0.0"))).getFirst();
            assertThat(result.resolved()).isFalse();
            assertThat(result.commonFix().version()).isNull();
        } else {
            assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(archive), mode))
                    .isInstanceOf(InvalidRequestException.class).hasMessageContaining(mode == AirgappedSnapshotService.ImportMode.REPLACE ? "base" : "profile");
            assertThat(service.findEpssScores(List.of("CVE-2026-9000"))).containsEntry("CVE-2026-9000", .25);
            assertThat(metadata.findById("osv").orElseThrow().getBundleId()).isEqualTo("base");
            assertThat(entries.countBySource("osv")).isZero();
            assertThat(service.findUnresolvedKeys(List.of(key))).hasSize(stored ? 1 : 0);
        }
    }

    @Test void distributionProfileRetainsAttributedFindingsWithoutConfirmingCompleteCoverage(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        String id = "GHSA-2345-6789-cfgh";
        String source = "https://github.com/github/advisory-database/blob/main/advisories/github-reviewed/2024/09/" + id + "/" + id + ".json";
        String known = """
                {"id":"%s","modified":"2024-09-01T00:00:00Z","affected":[{"package":{"ecosystem":"npm","name":"example"},
                "database_specific":{"source":"%s"},"ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"2.0.0"}]}]}]}
                """.formatted(id, source);
        String other = known.replace(id, "OTHER-fixture").replace(source, "https://example.invalid/source");
        Files.write(directory.resolve("osv-npm-all.zip"), bundle(Map.of("known.json", known, "other.json", other)));
        Files.writeString(directory.resolve("osv-npm-all.zip.lastmodified"), java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
        Path wanted = directory.resolve("wanted.jsonl");
        Files.writeString(wanted, """
                {"ecosystem":"npm","name":"example","version":"1.0.0"}
                {"ecosystem":"npm","name":"absent","version":"1.0.0"}
                """);
        Path baseline = directory.resolve("base.zip");
        for (boolean delta : List.of(false, true)) {
            Path output = delta ? directory.resolve("delta.zip") : baseline;
            var args = new java.util.ArrayList<>(List.of("build", "--distribution-profile", "github-attributed", "--wanted", wanted.toString(),
                    "--offline-sources", directory.toString(), "--out", output.toString()));
            if (delta) args.addAll(List.of("--since", baseline.toString()));
            int exit = org.springframework.test.util.ReflectionTestUtils.invokeMethod(new com.salkcoding.oswl.vdb.VdbBuilderCli(),
                    "run", (Object) args.toArray(String[]::new));
            assertThat(exit).isZero();
            byte[] bytes = Files.readAllBytes(output);
            assertThat(exportedMeta(bytes).path("distributionProfile").asText()).isEqualTo("github-attributed");
            assertThat(exportedMeta(bytes).path("resolvedCount").asInt()).isZero();
            service.importBundle(new ByteArrayInputStream(bytes), delta ? AirgappedSnapshotService.ImportMode.MERGE : AirgappedSnapshotService.ImportMode.REPLACE);
            assertThat(service.findOsvVulns(List.of("NPM|example|1.0.0")).get("NPM|example|1.0.0"))
                    .extracting(AirgappedSnapshotService.SnapshotVuln::osvId).containsExactly(id);
            var results = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(
                    new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "example", "1.0.0"),
                    new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "absent", "1.0.0")));
            assertThat(results).allSatisfy(result -> {
                assertThat(result.resolved()).isFalse();
                assertThat(result.commonFix().version()).isNull();
            });
        }
        Path rejected = directory.resolve("rejected.zip");
        Files.writeString(rejected, "preserve");
        int exit = org.springframework.test.util.ReflectionTestUtils.invokeMethod(new com.salkcoding.oswl.vdb.VdbBuilderCli(),
                "run", (Object) new String[] {"build", "--sources", "osv", "--wanted", wanted.toString(), "--since", baseline.toString(),
                        "--offline-sources", directory.toString(), "--out", rejected.toString()});
        assertThat(exit).isEqualTo(1);
        assertThat(Files.readString(rejected)).isEqualTo("preserve");
    }

    @Test void manyAttributedOriginalsRemainImportableWithoutLosingSourceLinks(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Map<String, String> originals = new LinkedHashMap<>();
        StringBuilder wantedRows = new StringBuilder();
        for (int i = 0; i < 4000; i++) {
            String digits = String.format("%04x", i);
            StringBuilder suffix = new StringBuilder();
            for (char digit : digits.toCharArray()) suffix.append("23456789cfghjmpq".charAt(Character.digit(digit, 16)));
            String id = "GHSA-2345-6789-" + suffix;
            String name = "fixture-" + i;
            String url = "https://github.com/github/advisory-database/blob/main/advisories/github-reviewed/2024/09/" + id + "/" + id + ".json";
            originals.put(id + ".json", """
                    {"id":"%s","modified":"2024-09-01T00:00:00Z","credits":[{"name":"Synthetic fixture author"}],
                    "affected":[{"package":{"ecosystem":"npm","name":"%s"},"database_specific":{"source":"%s"},
                    "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"2.0.0"}]}]}]}
                    """.formatted(id, name, url));
            wantedRows.append("{\"ecosystem\":\"npm\",\"name\":\"").append(name).append("\",\"version\":\"1.0.0\"}\n");
        }
        Files.write(directory.resolve("osv-npm-all.zip"), bundle(originals));
        Files.writeString(directory.resolve("osv-npm-all.zip.lastmodified"), java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
        Path wanted = directory.resolve("wanted.jsonl");
        Files.writeString(wanted, wantedRows);
        Path output = directory.resolve("bundle.zip");
        int exit = org.springframework.test.util.ReflectionTestUtils.invokeMethod(new com.salkcoding.oswl.vdb.VdbBuilderCli(),
                "run", (Object) new String[] {"build", "--sources", "osv", "--wanted", wanted.toString(),
                        "--offline-sources", directory.toString(), "--out", output.toString()});
        assertThat(exit).isZero();
        byte[] bytes = Files.readAllBytes(output);
        service.importBundle(new ByteArrayInputStream(bytes));
        assertThat(entries.countBySource("osv")).isEqualTo(4000);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        for (int i = 0; i < 4000; i++) {
            String key = "NPM|fixture-" + i + "|1.0.0";
            var stored = service.findOsvVulns(List.of(key)).get(key).getFirst();
            assertThat(stored.osvAdvisory()).isEqualTo(mapper.readTree(originals.get(stored.osvId() + ".json")));
        }
    }

    @Test void cliAttributedOriginalRetainsFixAndCreditsAndExportsNotices(
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        String id = "GHSA-2345-6789-cfgh";
        String source = "https://github.com/github/advisory-database/blob/main/advisories/github-reviewed/2024/09/" + id + "/" + id + ".json";
        String original = """
                {"id":"%s","modified":"2024-09-01T00:00:00Z","credits":[{"name":"Synthetic fixture author"}],
                "affected":[{"package":{"ecosystem":"npm","name":"example"},"database_specific":{"source":"%s"},
                "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"2.0.0"}]}]}]}
                """.formatted(id, source);
        Files.write(directory.resolve("osv-npm-all.zip"), bundle(Map.of("fixture.json", original)));
        Files.writeString(directory.resolve("osv-npm-all.zip.lastmodified"), java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
        Path wanted = directory.resolve("wanted.jsonl");
        Files.writeString(wanted, "{\"ecosystem\":\"npm\",\"name\":\"example\",\"version\":\"1.0.0\"}\n");
        Path output = directory.resolve("bundle.zip");
        int exit = org.springframework.test.util.ReflectionTestUtils.invokeMethod(new com.salkcoding.oswl.vdb.VdbBuilderCli(),
                "run", (Object) new String[] {"build", "--sources", "osv", "--wanted", wanted.toString(),
                        "--offline-sources", directory.toString(), "--out", output.toString()});
        assertThat(exit).isZero();
        byte[] bytes = Files.readAllBytes(output);
        var notice = exportedMeta(bytes).path("dataNotices");
        assertThat(notice.path("githubAdvisoryDatabase").path("retainedOriginals").path("recordLocation").asText())
                .isEqualTo("osv.jsonl: vulns[].osvAdvisory");
        assertThat(notice.path("githubAdvisoryDatabase").path("retainedOriginals").path("sourceField").asText())
                .isEqualTo("affected[].database_specific.source");
        assertThat(notice.path("githubAdvisoryDatabase").path("license").asText()).isEqualTo("CC-BY-4.0");
        for (int round = 0; round < 2; round++) {
            service.importBundle(new ByteArrayInputStream(bytes), round == 0
                    ? AirgappedSnapshotService.ImportMode.REPLACE : AirgappedSnapshotService.ImportMode.MERGE);
            var stored = service.findOsvVulns(List.of("NPM|example|1.0.0")).get("NPM|example|1.0.0").getFirst();
            assertThat(stored.osvAdvisory()).isEqualTo(new com.fasterxml.jackson.databind.ObjectMapper().readTree(original));
            var result = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(
                    new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "example", "1.0.0"))).getFirst();
            assertThat(result.resolved()).isTrue();
            assertThat(result.commonFix().version()).isEqualTo("2.0.0");
            assertThat(exportedMeta(service.exportBundle()).path("upstreamDataNotices"))
                    .anySatisfy(retained -> assertThat(retained).isEqualTo(notice));
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void cliEmptyOsvLookupRetainsCoverage(boolean filtered, boolean delta, @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Path wanted = directory.resolve("wanted.jsonl");
        Files.writeString(wanted, "{\"ecosystem\":\"npm\",\"name\":\"example\",\"version\":\"1.0.0\"}");
        Files.writeString(directory.resolve("osv-npm-all.zip.lastmodified"), java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
        Path base = directory.resolve("base.zip");
        if (delta) {
            Files.write(directory.resolve("osv-npm-all.zip"), bundle(Map.of("known.json", """
                    {"id":"OSV-known","modified":"2026-01-01T00:00:00Z","affected":[{"package":{"ecosystem":"npm","name":"example"},
                    "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"2.0.0"}]}]}]}
                    """)));
            Integer baseExit = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    new com.salkcoding.oswl.vdb.VdbBuilderCli(), "run", (Object) new String[]{"build", "--sources", "osv",
                            "--wanted", wanted.toString(), "--offline-sources", directory.toString(), "--out", base.toString()});
            assertThat(baseExit).isZero();
            service.importBundle(new ByteArrayInputStream(Files.readAllBytes(base)));
            var prior = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(
                    new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "example", "1.0.0"))).getFirst();
            assertThat(prior.resolved()).isTrue();
            assertThat(prior.vulns()).extracting(com.salkcoding.oswl.client.OsvClient.OsvVuln::osvId).containsExactly("OSV-known");
        }
        Files.write(directory.resolve("osv-npm-all.zip"), bundle(Map.of("other.json", """
                {"id":"OSV-other","modified":"2026-01-01T00:00:00Z","affected":[{"package":{"ecosystem":"npm","name":"other"},
                "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"2.0.0"}]}]}]}
                """)));
        Path output = directory.resolve("full.zip");
        var args = new ArrayList<>(List.of("build", "--sources", "osv", "--wanted", wanted.toString(),
                "--offline-sources", directory.toString(), "--out", output.toString()));
        if (filtered) args.addAll(List.of("--ecosystems", "PYPI"));
        if (delta) args.addAll(List.of("--since", base.toString()));
        Integer exit = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                new com.salkcoding.oswl.vdb.VdbBuilderCli(), "run", (Object) args.toArray(String[]::new));
        assertThat(exit).isZero();
        service.importBundle(new ByteArrayInputStream(Files.readAllBytes(output)), delta
                ? AirgappedSnapshotService.ImportMode.MERGE : AirgappedSnapshotService.ImportMode.REPLACE);
        var result = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(
                new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "example", "1.0.0"))).getFirst();
        assertThat(result.resolved()).isEqualTo(!filtered);
        assertThat(result.vulns()).isEmpty();
        assertThat(service.findUnresolvedKeys(List.of("NPM|example|1.0.0")).isEmpty()).isEqualTo(!filtered);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void cliDeltaKeepsUnselectedSourceData(boolean withWanted, @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        Path base = directory.resolve("base.zip");
        byte[] baseline = versionedBundle(Map.of("unresolved.jsonl", "{\"ecosystem\":\"npm\",\"name\":\"old\",\"version\":\"1\"}", "kev.jsonl", "{\"cveId\":\"CVE-2026-1000\"}",
                "epss.jsonl", "{\"cveId\":\"CVE-2026-1000\",\"score\":0.4}",
                "meta.json", "{\"sources\":{\"epss\":{\"asOf\":\"2020-01-01\"}}}"), "base", null);
        Files.write(base, baseline);
        service.importBundle(new ByteArrayInputStream(baseline));
        var priorEpssStatus = service.status().stream().filter(status -> status.source().equals("epss")).findFirst().orElseThrow();
        Files.writeString(directory.resolve("kev.json"), "{\"count\":1,\"catalogVersion\":\"fixture\",\"dateReleased\":\"2026-01-01T00:00:00Z\",\"vulnerabilities\":[{\"cveID\":\"CVE-2026-1001\"}]}");
        Path output = directory.resolve("delta.zip");
        var args = new ArrayList<>(List.of("build", "--sources", "kev", "--offline-sources", directory.toString(),
                "--since", base.toString(), "--out", output.toString()));
        if (withWanted) {
            Path wanted = directory.resolve("wanted.jsonl");
            Files.writeString(wanted, "{\"ecosystem\":\"npm\",\"name\":\"current\",\"version\":\"1\"}");
            args.addAll(List.of("--wanted", wanted.toString()));
        }
        Integer exit = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                new com.salkcoding.oswl.vdb.VdbBuilderCli(), "run", (Object) args.toArray(String[]::new));
        assertThat(exit).isZero();
        var meta = exportedMeta(Files.readAllBytes(output));
        assertThat(meta.path("sources").has("epss")).isFalse();
        assertThat(meta.path("files").has("epss.jsonl")).isFalse();
        assertThat(meta.path("files").has("unresolved.jsonl")).isFalse();
        service.importBundle(new ByteArrayInputStream(Files.readAllBytes(output)), AirgappedSnapshotService.ImportMode.MERGE);
        assertThat(service.findEpssScores(List.of("CVE-2026-1000"))).containsEntry("CVE-2026-1000", 0.4);
        assertThat(service.status().stream().filter(status -> status.source().equals("epss")).findFirst().orElseThrow())
                .isEqualTo(priorEpssStatus);
        assertThat(service.loadKevCveIds()).containsExactly("CVE-2026-1001");
        assertThat(service.findUnresolvedKeys(List.of("NPM|old|1", "NPM|current|1"))).containsExactly("NPM|old|1");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {true, false})
    void cliDeltaPreservesAndClearsExplicitOsvUncertainty(boolean initiallyPartial,
            @org.junit.jupiter.api.io.TempDir Path directory) throws Exception {
        String known = """
                {"id":"OSV-fixture","modified":"2026-01-01T00:00:00Z","affected":[{"package":{"ecosystem":"npm","name":"example"},
                "ranges":[{"type":"SEMVER","events":[{"introduced":"0"},{"fixed":"1.0.1"}]}]}]}
                """;
        String unknown = """
                {"id":"OSV-unknown","modified":"2026-01-01T00:00:00Z","affected":[{"package":{"ecosystem":"npm","name":"example"},
                "ranges":[{"type":"GIT","events":[{"introduced":"0"}]}]}]}
                """;
        Path wanted = directory.resolve("wanted.jsonl");
        Files.writeString(wanted, """
                {"ecosystem":"npm","name":"example","version":"1.0.0"}
                """);
        Files.writeString(directory.resolve("osv-npm-all.zip.lastmodified"), java.time.LocalDate.now(java.time.ZoneOffset.UTC).toString());
        Path base = directory.resolve("base.zip");
        for (int step = 0; step < 2; step++) {
            boolean partial = step == 0 ? initiallyPartial : !initiallyPartial;
            Files.write(directory.resolve("osv-npm-all.zip"), bundle(partial
                    ? Map.of("known.json", known, "unknown.json", unknown) : Map.of("known.json", known)));
            Path output = step == 0 ? base : directory.resolve("delta.zip");
            var args = new ArrayList<>(List.of("build", "--sources", "osv", "--wanted", wanted.toString(),
                    "--offline-sources", directory.toString(), "--out", output.toString()));
            if (step != 0) args.addAll(List.of("--mode", "delta", "--since", base.toString()));
            Integer exit = org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                    new com.salkcoding.oswl.vdb.VdbBuilderCli(), "run", (Object) args.toArray(String[]::new));
            assertThat(exit).isZero();
            var notice = exportedMeta(Files.readAllBytes(output)).path("dataNotices");
            assertThat(notice.path("githubAdvisoryDatabase").path("license").asText()).isEqualTo("CC-BY-4.0");
            assertThat(notice.path("githubAdvisoryDatabase").path("sourceUrl").asText())
                    .isEqualTo("https://github.com/github/advisory-database");
            assertThat(notice.path("githubAdvisoryDatabase").path("licenseUrl").asText())
                    .isEqualTo("https://creativecommons.org/licenses/by/4.0/");
            assertThat(notice.path("scope").asText()).contains("not a redistribution clearance");
            assertThat(notice.path("changes").asText()).contains("normalized");
            try (var input = Files.newInputStream(output)) {
                service.importBundle(input, step == 0 ? AirgappedSnapshotService.ImportMode.REPLACE
                        : AirgappedSnapshotService.ImportMode.MERGE);
            }
            assertThat(exportedMeta(service.exportBundle()).path("upstreamDataNotices"))
                    .anySatisfy(retained -> assertThat(retained).isEqualTo(notice));
            String key = "NPM|example|1.0.0";
            assertThat(service.findUnresolvedKeys(List.of(key)).contains(key)).isEqualTo(partial);
            var result = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(
                    new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "example", "1.0.0"))).getFirst();
            assertThat(result.resolved()).isEqualTo(!partial);
            assertThat(result.vulns()).extracting(com.salkcoding.oswl.client.OsvClient.OsvVuln::osvId)
                    .containsExactly("OSV-fixture");
            assertThat(result.vulns().getFirst().fixVersion()).isEqualTo("1.0.1");
            assertThat(result.commonFix().version()).isNull();
            assertThat(result.commonFix().reason()).isEqualTo(partial ? "INCOMPLETE_LOOKUP" : "NO_RANGE_EVIDENCE");
        }
    }

    @Autowired com.salkcoding.oswl.repository.vulnerability.LibraryRepository libraries;
    @Autowired com.salkcoding.oswl.repository.vulnerability.CveRepository cves;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"false,false", "false,true", "true,false", "true,true"})
    void exportedLegacyCacheRemainsUnresolvedWithoutDroppingFindings(boolean verified, boolean finding) throws Exception {
        String name = "legacy-cache-"+UUID.randomUUID();
        var library = com.salkcoding.oswl.domain.entity.vulnerability.Library.builder().name(name).version("1").ecosystem("NPM").build();
        if (verified) library.recordLookupOutcomes(Map.of("OSV","RESOLVED"));
        library.markFetched();
        library = libraries.saveAndFlush(library);
        if (finding) cves.saveAndFlush(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder().library(library)
                .cveId("CVE-2026-123450").sources(Set.of(com.salkcoding.oswl.domain.enums.CveSource.OSV)).build());
        Map<String,String> files = new HashMap<>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(service.exportBundle()))) {
            ZipEntry file;
            while ((file=zip.getNextEntry())!=null) files.put(file.getName(),new String(zip.readAllBytes(),StandardCharsets.UTF_8));
        }
        assertThat(files.getOrDefault("unresolved.jsonl","").contains(name)).isEqualTo(!verified);
        if (finding) {
            var line = files.get("osv.jsonl").lines().filter(value -> value.contains(name)).findFirst().orElseThrow();
            assertThat(line).contains("CVE-2026-123450");
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"match", "changed-content", "missing", "extra", "legacy-assessment", "failed-lookup", "oversized"})
    void exportOriginalsMustMatchTheContentUsedForTheStoredAssessment(String state) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        String name = "original-export-" + java.util.UUID.randomUUID();
        var current = json.readTree("""
                {"id":"OSV-current","modified":"2026-01-01T00:00:00Z","credits":[{"name":"fixture author"}],
                "references":[{"type":"ADVISORY","url":"https://example.invalid/fixture"}],"database_specific":{"notice":"자체 검증 원문"},
                "affected":[{"package":{"ecosystem":"npm","name":"%s"},"ranges":[{"type":"SEMVER",
                "events":[{"introduced":"0"},{"fixed":"2.0.0"}]}]}]}
                """.formatted(name));
        var later = json.readTree(current.toString().replace("OSV-current", "OSV-later")
                .replace("\"introduced\":\"0\"", "\"introduced\":\"2.0.0\"").replace("\"fixed\":\"2.0.0\"", "\"fixed\":\"3.0.0\""));
        var records = new ArrayList<Map<String, Object>>();
        if (state.equals("oversized")) {
            byte[] content = new byte[300_000];
            new java.util.Random(42).nextBytes(content);
            ((com.fasterxml.jackson.databind.node.ObjectNode) current).put("summary", java.util.HexFormat.of().formatHex(content));
        }
        for (var raw : List.of(current, later)) records.add(Map.of("osvId", raw.path("id").asText(), "osvAdvisory", raw));
        String key = AirgappedSnapshotService.componentKey("npm", name, "1.0.0");
        var entry = entries.saveAndFlush(SnapshotEntry.builder().source("osv").entryKey(key).payload(json.writeValueAsString(records)).build());
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("osv").recordCount(1)
                .importedAt(java.time.LocalDateTime.now()).sourceAsOf(java.time.LocalDate.now())
                .dataNotices("[{\"credit\":\"fixture author\"}]").build());
        var query = new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", name, "1.0.0");
        var assessment = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(query)).getFirst();
        assertThat(assessment.resolved()).isTrue();
        assertThat(assessment.commonFix().version()).isEqualTo("3.0.0");
        var library = com.salkcoding.oswl.domain.entity.vulnerability.Library.builder().name(name).version("1.0.0").ecosystem("NPM").build();
        library.markFetched();
        library.recordLookupOutcomes(Map.of("OSV", state.equals("failed-lookup") ? "UNAVAILABLE" : "RESOLVED"));
        library.recordOsvFixAssessment("3.0.0", "SOURCE_FIXED_EVENT", assessment.advisoryRevisions(), java.util.Set.of("OSV-current"),
                java.time.Instant.now().plusSeconds(3600), state.equals("legacy-assessment") ? Map.of() : assessment.advisoryDigests());
        library = libraries.saveAndFlush(library);
        cves.saveAndFlush(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder().library(library)
                .ghsaId("OSV-current").fixVersion("2.0.0").summary(current.path("summary").asText(null)).build());
        if (state.equals("changed-content")) ((com.fasterxml.jackson.databind.node.ObjectNode) current).put("summary", "changed at same revision");
        if (state.equals("extra")) records.add(Map.of("osvId", "OSV-extra"));
        entries.deleteById(entry.getId());
        if (!state.equals("missing")) entries.saveAndFlush(SnapshotEntry.builder().source("osv").entryKey(key)
                .payload(json.writeValueAsString(records)).build());
        if (state.equals("oversized")) {
            assertThatThrownBy(service::exportBundle).isInstanceOf(InvalidRequestException.class).hasMessageContaining("1 MiB");
            return;
        }
        byte[] exported = service.exportBundle();
        com.fasterxml.jackson.databind.JsonNode line = null;
        try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(exported))) {
            java.util.zip.ZipEntry file;
            while ((file = zip.getNextEntry()) != null) if (file.getName().equals("osv.jsonl")) {
                for (String text : new String(zip.readAllBytes(), StandardCharsets.UTF_8).lines().toList()) {
                    var candidate = json.readTree(text);
                    if (candidate.path("name").asText().equals(name)) line = candidate;
                }
            }
        }
        assertThat(line).isNotNull();
        if (state.equals("match")) {
            assertThat(exportedMeta(exported).path("formatVersion").asInt()).isEqualTo(3);
            assertThat(line.path("vulns").size()).isEqualTo(2);
            assertThat(line.path("vulns").get(0).path("osvAdvisory")).isEqualTo(current);
            assertThat(line.path("vulns").get(1).path("osvAdvisory")).isEqualTo(later);
            service.importBundle(new ByteArrayInputStream(exported));
            assertThat(metadata.findById("osv").orElseThrow().getFormatVersion()).isEqualTo(3);
            var restored = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(query)).getFirst();
            assertThat(restored.advisoryDigests()).isEqualTo(assessment.advisoryDigests());
            assertThat(restored.resolved()).isFalse(); // A scan export does not renew the source's date.
        } else {
            assertThat(line.path("vulns").size()).isEqualTo(1);
            assertThat(line.path("vulns").get(0).path("osvAdvisory").isObject()).isFalse();
        }
    }

    @Test void exportIncludesItsOwnNoticesInTheRoundTripBudget() {
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("epss")
                .recordCount(1).importedAt(java.time.LocalDateTime.now())
                .dataNotices("[{\"credit\":\"" + "x".repeat(512 * 1024 - 100) + "\"}]").build());
        assertThatThrownBy(service::exportBundle).isInstanceOf(InvalidRequestException.class).hasMessageContaining("512 KiB");
    }

    @Test void cumulativeNoticeBudgetRollsBackTheMergeInsteadOfDroppingOldNotices() throws Exception {
        byte[] noticeBytes = new byte[150_000];
        new java.util.Random(17).nextBytes(noticeBytes);
        String credit = "credit-" + java.util.HexFormat.of().formatHex(noticeBytes);
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("meta.json", json.writeValueAsString(Map.of("dataNotices", Map.of("credit", credit))),
                "epss.jsonl", "{\"cveId\":\"CVE-2026-9002\",\"score\":0.4}"))));
        String more = json.writeValueAsString(Map.of("dataNotices", Map.of("credit", "different-" + credit)));
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("meta.json", more,
                "epss.jsonl", "{\"cveId\":\"CVE-2026-9003\",\"score\":0.6}"))), AirgappedSnapshotService.ImportMode.MERGE))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("512 KiB");
        assertThat(service.findEpssScores(List.of("CVE-2026-9002", "CVE-2026-9003"))).containsOnlyKeys("CVE-2026-9002");
        assertThat(exportedMeta(service.exportBundle()).path("upstreamDataNotices").get(0).path("credit").asText()).isEqualTo(credit);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"\"dataNotices\":null", "\"dataNotices\":[]",
            "\"dataNotices\":\"notice\"", "\"upstreamDataNotices\":{}", "\"upstreamDataNotices\":[null]",
            "\"upstreamDataNotices\":[\"notice\"]"})
    void invalidNoticesCannotEraseExistingSourceData(String notice) throws Exception {
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("meta.json", "{" + notice + "}",
                "epss.jsonl", "{\"cveId\":\"CVE-2026-9001\",\"score\":0.4}")))))
                .isInstanceOf(InvalidRequestException.class);
        assertOldSource();
    }

    @Test void unreadableStoredNoticesPreventExportInsteadOfSilentlyDroppingCredits() {
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("epss")
                .recordCount(1).importedAt(java.time.LocalDateTime.now()).dataNotices("broken").build());
        assertThatThrownBy(service::exportBundle).isInstanceOf(InvalidRequestException.class);
    }

    @Test void noticeMigrationIsAdditiveAndPreservesLongUnicodePayloadsWhenRepeated() throws Exception {
        try (var connection = java.sql.DriverManager.getConnection("jdbc:h2:mem:notice-migration-" + java.util.UUID.randomUUID())) {
            try (var statement = connection.createStatement()) {
                statement.execute("CREATE TABLE airgapped_snapshot_meta(source VARCHAR(20) PRIMARY KEY)");
                statement.execute("INSERT INTO airgapped_snapshot_meta VALUES('osv')");
                String migration = java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V39__snapshot_data_notices.sql"));
                statement.execute(migration);
                try (var rows = statement.executeQuery("SELECT data_notices FROM airgapped_snapshot_meta")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isNull();
                }
                String notice = "[{\"credit\":\"" + "© 원문 고지 日本語 ".repeat(1000) + "\"}]";
                try (var update = connection.prepareStatement("UPDATE airgapped_snapshot_meta SET data_notices=?")) {
                    update.setString(1, notice);
                    assertThat(update.executeUpdate()).isEqualTo(1);
                }
                statement.execute(migration);
                try (var rows = statement.executeQuery("SELECT data_notices FROM airgapped_snapshot_meta")) {
                    assertThat(rows.next()).isTrue();
                    assertThat(rows.getString(1)).isEqualTo(notice);
                }
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"MERGE", "REPLACE"})
    void importedNoticesSurvivePersistenceAndRepeatedExportsWithoutNesting(String mode) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        for (String owner : List.of("first", "second")) {
            String meta = json.writeValueAsString(Map.of("dataNotices", Map.of("credit", owner, "notice", "원문 고지 © " + owner)));
            service.importBundle(new ByteArrayInputStream(bundle(Map.of("meta.json", meta, "epss.jsonl",
                    "{\"cveId\":\"CVE-2026-" + (owner.equals("first") ? "8001" : "8002") + "\",\"score\":0.4}"))), AirgappedSnapshotService.ImportMode.valueOf(mode));
        }
        byte[] exported = service.exportBundle();
        var notices = exportedMeta(exported).path("upstreamDataNotices");
        assertThat(notices.size()).isEqualTo(mode.equals("MERGE") ? 2 : 1);
        assertThat(notices.toString()).contains("second", "원문 고지 ©");
        if (mode.equals("MERGE")) assertThat(notices.toString()).contains("first");
        service.importBundle(new ByteArrayInputStream(exported));
        byte[] next = service.exportBundle();
        var afterOne = exportedMeta(next).path("upstreamDataNotices");
        assertThat(afterOne.size()).isEqualTo(notices.size() + 1);
        service.importBundle(new ByteArrayInputStream(next));
        assertThat(exportedMeta(service.exportBundle()).path("upstreamDataNotices")).isEqualTo(afterOne);
    }

    private com.fasterxml.jackson.databind.JsonNode exportedMeta(byte[] content) throws Exception {
        try (var zip = new java.util.zip.ZipInputStream(new ByteArrayInputStream(content))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) if (entry.getName().equals("meta.json"))
                return new com.fasterxml.jackson.databind.ObjectMapper().readTree(zip.readAllBytes());
        }
        throw new AssertionError("meta.json absent");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 6, 7, 8, -1, 999})
    void candidateExpiryUsesTheOriginalSourceDateRatherThanTheLookupDate(int age) {
        var asOf = age == 999 ? null : java.time.LocalDate.now().minusDays(age);
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("osv")
                .sourceAsOf(asOf).importedAt(java.time.LocalDateTime.now()).build());
        var expiry = service.sourceEvidenceValidUntil("osv");
        String name = "expiry-" + age;
        entries.saveAndFlush(SnapshotEntry.builder().source("osv").entryKey("NPM|" + name + "|1.0.0").payload("[]").build());
        var result = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(
                new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", name, "1.0.0"))).getFirst();
        assertThat(result.validUntil()).isEqualTo(expiry);
        if (age < 0 || age == 999) assertThat(expiry).isNull();
        else {
            assertThat(expiry).isEqualTo(asOf.plusDays(8).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant());
            assertThat(expiry.isAfter(java.time.Instant.now())).isEqualTo(age <= 7);
        }
        assertThat(service.isSourceStaleOrUndated("osv")).isEqualTo(age < 0 || age > 7);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 499})
    void mergeOfRepeatedComponentKeysPreservesLastOriginalAcrossBatchBoundaries(int preceding) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var lines = new ArrayList<String>();
        for (int i = 0; i < preceding; i++) {
            lines.add(json.writeValueAsString(Map.of("ecosystem", "npm", "name", "filler-" + i, "version", "1.0.0", "vulns", List.of())));
        }
        com.fasterxml.jackson.databind.JsonNode lastOriginal = null;
        for (int revision = 2; revision <= 3; revision++) {
            lastOriginal = json.readTree("""
                    {"id":"OSV-merge","modified":"2026-01-0%sT00:00:00Z","credits":[{"name":"Owned revision %s"}],
                    "affected":[{"package":{"ecosystem":"npm","name":"fixture"},"ranges":[{"type":"SEMVER",
                    "events":[{"introduced":"0"},{"fixed":"%s.0.0"}]}]}]}
                    """.formatted(revision, revision, revision));
            lines.add(json.writeValueAsString(Map.of("ecosystem", "npm", "name", "fixture", "version", "1.0.0",
                    "vulns", List.of(Map.of("osvId", "OSV-merge", "fixVersion", revision + ".0.0", "osvAdvisory", lastOriginal)))));
        }
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("osv.jsonl", String.join("\n", lines)))), AirgappedSnapshotService.ImportMode.MERGE);
        String key = AirgappedSnapshotService.componentKey("npm", "fixture", "1.0.0");
        var stored = service.findOsvVulns(List.of(key)).get(key);
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().fixVersion()).isEqualTo("3.0.0");
        assertThat(stored.getFirst().osvAdvisory()).isEqualTo(lastOriginal);
        assertThat(entries.countBySource("osv")).isEqualTo(preceding + 1);
        assertThat(service.findEpssScores(List.of("CVE-2026-9000"))).containsEntry("CVE-2026-9000", 0.25);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 8, 999})
    void suppliedOsvEvidenceSupportsCommonFixParityWithoutRefreshingOldData(int age) throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        List<com.fasterxml.jackson.databind.JsonNode> originals = new ArrayList<>();
        List<Map<String, Object>> vulns = new ArrayList<>();
        for (int i = 2; i <= 3; i++) {
            String id = "OSV-owned-" + i;
            var advisory = json.valueToTree(Map.of("id", id, "modified", "2026-01-01T00:00:00Z",
                    "credits", List.of(Map.of("name", "OsWL synthetic fixture")),
                    "affected", List.of(Map.of("package", Map.of("ecosystem", "npm", "name", "fixture"),
                            "ranges", List.of(Map.of("type", "SEMVER", "events", List.of(
                                    Map.of("introduced", "0"), Map.of("fixed", i + ".0.0"))))))));
            originals.add(advisory);
            vulns.add(Map.of("osvId", id, "fixVersion", i + ".0.0", "osvAdvisory", advisory));
        }
        String line = json.writeValueAsString(Map.of("ecosystem", "npm", "name", "fixture", "version", "1.0.0", "vulns", vulns));
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(line.getBytes(StandardCharsets.UTF_8)));
        String source = age == 999 ? "{}" : "{\"asOf\":\"" + java.time.LocalDate.now().minusDays(age) + "\"}";
        String manifest = "{\"formatVersion\":2,\"sources\":{\"osv\":" + source
                + "},\"files\":{\"osv.jsonl\":{\"sha256\":\"" + hash + "\",\"lines\":1}}}";
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("osv.jsonl", line, "meta.json", manifest))));
        String key = AirgappedSnapshotService.componentKey("npm", "fixture", "1.0.0");
        assertThat(service.findOsvVulns(List.of(key)).get(key)).extracting(AirgappedSnapshotService.SnapshotVuln::osvAdvisory)
                .containsExactlyElementsOf(originals);
        var query = new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "fixture", "1.0.0");
        var offline = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(query)).getFirst();
        var builder = org.springframework.web.client.RestClient.builder().baseUrl("https://api.osv.dev");
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api.osv.dev/v1/querybatch"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        json.writeValueAsString(Map.of("results", List.of(Map.of("vulns", originals)))), org.springframework.http.MediaType.APPLICATION_JSON));
        for (var original : originals) {
            server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api.osv.dev/v1/vulns/" + original.path("id").asText()))
                    .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(original.toString(), org.springframework.http.MediaType.APPLICATION_JSON));
        }
        var onlineClient = new com.salkcoding.oswl.client.OsvClient();
        org.springframework.test.util.ReflectionTestUtils.setField(onlineClient, "restClient", builder.build());
        var online = onlineClient.queryBatch(List.of(query)).getFirst();
        assertThat(online.commonFix().version()).isEqualTo("3.0.0");
        assertThat(offline.resolved()).isEqualTo(age == 0);
        if (age == 0) assertThat(offline.commonFix()).isEqualTo(online.commonFix());
        else assertThat(offline.commonFix().version()).isNull();
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"identity", "modified", "affected"})
    void invalidOriginalAdvisoryRejectsImportWithoutDeletingExistingData(String invalid) throws Exception {
        String line = """
                {"ecosystem":"npm","name":"fixture","version":"1.0.0","vulns":[{
                "osvId":"OSV-one","osvAdvisory":{"id":"%s","modified":"%s","affected":%s}}]}
                """.formatted(invalid.equals("identity") ? "OSV-other" : "OSV-one",
                invalid.equals("modified") ? "unknown" : "2026-01-01T00:00:00Z", invalid.equals("affected") ? "{}" : "[]");
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("osv.jsonl", line)))))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(entries.findAll()).singleElement().satisfies(row -> assertThat(row.getEntryKey()).isEqualTo("CVE-2026-9000"));
    }
    @Test void exportedManifestCarriesDataAttributionAndTransformationNotice() throws Exception {
        byte[] exported = service.exportBundle();
        com.fasterxml.jackson.databind.JsonNode meta = null;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(exported), StandardCharsets.UTF_8)) {
            ZipEntry item;
            while ((item = zip.getNextEntry()) != null) {
                if (item.getName().equals("meta.json")) {
                    meta = new com.fasterxml.jackson.databind.ObjectMapper().readTree(zip.readAllBytes());
                }
            }
        }
        assertThat(meta).isNotNull();
        var notices = meta.path("dataNotices");
        assertThat(notices.path("githubAdvisoryDatabase").path("license").asText()).isEqualTo("CC-BY-4.0");
        assertThat(notices.path("githubAdvisoryDatabase").path("licenseUrl").asText())
                .isEqualTo("https://creativecommons.org/licenses/by/4.0/");
        assertThat(notices.path("githubAdvisoryDatabase").path("sourceUrl").asText())
                .isEqualTo("https://github.com/github/advisory-database");
        assertThat(notices.path("changes").asText()).contains("normalized", "not original advisory documents");
        assertThat(notices.path("scope").asText()).contains("not a redistribution clearance");
        service.importBundle(new ByteArrayInputStream(exported));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 8, 999})
    void officialNugetFindingSurvivesDatabaseImportWithoutRefreshingStaleFixes(int age) throws Exception {
        byte[] raw;
        try (var input = getClass().getResourceAsStream("/advisories/GHSA-hh2w-p6rv-4g7w.json")) {
            assertThat(input).isNotNull();
            raw = input.readAllBytes();
        }
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        Map<String, List<AirgappedSnapshotService.SnapshotVuln>> found = new LinkedHashMap<>();
        Set<String> unresolved = new LinkedHashSet<>();
        var bulkConstructor = Class.forName("com.salkcoding.oswl.vdb.OsvBulkSource")
                .getDeclaredConstructor(com.fasterxml.jackson.databind.ObjectMapper.class);
        bulkConstructor.setAccessible(true);
        org.springframework.test.util.ReflectionTestUtils.invokeMethod(bulkConstructor.newInstance(json),
                "processVulnEntry", raw, "NUGET", Map.of("System.Text.Json", Set.of("8.0.3")), found, unresolved);
        String key = AirgappedSnapshotService.componentKey("NuGet", "System.Text.Json", "8.0.3");
        assertThat(unresolved).isEmpty();
        assertThat(found.get(key)).hasSize(1);
        String line = json.writeValueAsString(Map.of("ecosystem", "NuGet", "name", "System.Text.Json",
                "version", "8.0.3", "vulns", found.get(key)));
        // The date is a simulated freshness condition, not the publication date of this fixture.
        String sourceDate = age == 999 ? "{}" : "{\"asOf\":\"" + java.time.LocalDate.now().minusDays(age) + "\"}";
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(line.getBytes(StandardCharsets.UTF_8)));
        String manifest = "{\"formatVersion\":2,\"sources\":{\"osv\":" + sourceDate
                + "},\"files\":{\"osv.jsonl\":{\"sha256\":\"" + hash + "\",\"lines\":1}}}";
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("osv.jsonl", line, "meta.json", manifest))));
        assertThat(metadata.findById("osv").orElseThrow().getSourceAsOf())
                .isEqualTo(age == 999 ? null : java.time.LocalDate.now().minusDays(age));
        var client = new com.salkcoding.oswl.client.OsvClient(service, true);
        var result = client.queryBatch(List.of(new com.salkcoding.oswl.client.OsvClient.OsvQuery(
                "NuGet", "System.Text.Json", "8.0.3"))).getFirst();
        assertThat(result.resolved()).isEqualTo(age == 0);
        assertThat(result.vulns()).singleElement().satisfies(v -> {
            assertThat(v.osvId()).isEqualTo("GHSA-hh2w-p6rv-4g7w");
            assertThat(v.cveId()).isEqualTo("CVE-2024-30105");
            assertThat(v.fixVersion()).isEqualTo(age == 0 ? "8.0.4" : null);
        });
        assertThat(service.findOsvVulns(List.of(key)).get(key).getFirst().fixVersion()).isEqualTo("8.0.4");
        assertThat(client.queryBatch(List.of(new com.salkcoding.oswl.client.OsvClient.OsvQuery(
                "NuGet", "System.Text.Json", "8.0.2"))).getFirst().resolved()).isFalse();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"", " ", "CVE-FIXTURE", "cve-2026-0001", "CVE-2026-123", "CVE-2026-0001"})
    void offlineNvdSeparatesInvalidIdentitiesFromPreservedEvidence(String id) {
        String key = AirgappedSnapshotService.componentKey("CONAN", "identity-fixture", "1.0.0");
        entries.saveAndFlush(SnapshotEntry.builder().source("nvd").entryKey(key)
                .payload("[{\"cveId\":\"CVE-2026-0002\"},{\"osvId\":\"fixture-alias\",\"cveId\":\"" + id
                        + "\"},{\"cveId\":\"CVE-2026-0003\"}]").build());
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("nvd")
                .recordCount(1).importedAt(java.time.LocalDateTime.now()).sourceAsOf(java.time.LocalDate.now()).build());
        var client = new com.salkcoding.oswl.client.NvdClient(service, true, null,
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
        var cpe = org.mockito.Mockito.mock(com.salkcoding.oswl.client.CpeMatchService.class);
        var source = new com.salkcoding.oswl.service.vulnerability.sources.NvdAdvisorySource(client, cpe);
        var actual = source.lookupSnapshot("identity-fixture", "1.0.0", null, client.findSnapshotByComponentKeys(List.of(key)).get(key));
        boolean valid = id.equals("CVE-2026-0001");
        assertThat(actual.lookupFailed()).isEqualTo(!valid);
        assertThat(actual.findings()).extracting(v -> v.cveId()).containsExactlyElementsOf(valid
                ? List.of("CVE-2026-0002", id, "CVE-2026-0003") : List.of("CVE-2026-0002", "CVE-2026-0003"));
        assertThat(service.findNvdVulns(List.of(key)).get(key)).hasSize(3);
        org.mockito.Mockito.verifyNoInteractions(cpe);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,true", "7,true", "8,false", "40,false", "-1,false", "999,false"})
    void offlineDepsDevAdvisoryRetainsEvidenceWithItsFreshness(int age, boolean current) {
        entries.saveAndFlush(SnapshotEntry.builder().source("depsdev-advisory").entryKey("GHSA-fixture")
                .payload("{\"ghsaId\":\"GHSA-fixture\",\"title\":\"Stored evidence\",\"aliases\":[\"CVE-2026-0001\"],\"cvss3Score\":7.5}").build());
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("depsdev-advisory")
                .recordCount(1).importedAt(java.time.LocalDateTime.now())
                .sourceAsOf(age == 999 ? null : java.time.LocalDate.now().minusDays(age)).build());
        var actual = new com.salkcoding.oswl.client.DepsDevClient(service, true)
                .getAdvisoriesBatch(List.of("GHSA-fixture", "GHSA-missing"));
        assertThat(actual).hasSize(2);
        assertThat(actual.get(1)).isNull();
        assertThat(actual.getFirst().current()).isEqualTo(current);
        assertThat(actual.getFirst().aliases()).containsExactly("CVE-2026-0001");
        assertThat(actual.getFirst().title()).isEqualTo("Stored evidence");
        assertThat(actual.getFirst().cvss3Score()).isEqualTo(7.5);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,true", "7,true", "8,false", "40,false", "-1,false", "999,false"})
    void offlineEpssWithholdsStaleScoresWithoutReplacingStoredEvidence(int age, boolean current) {
        entries.deleteAll();
        entries.saveAndFlush(SnapshotEntry.builder().source("epss").entryKey("CVE-2026-1000").payload("0.25").build());
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("epss")
                .recordCount(1).importedAt(java.time.LocalDateTime.now())
                .sourceAsOf(age == 999 ? null : java.time.LocalDate.now().minusDays(age)).build());
        var actual = new com.salkcoding.oswl.client.EpssClient(service, true).fetchScores(List.of("CVE-2026-1000"));
        if (current) assertThat(actual).containsExactly(entry("CVE-2026-1000", 0.25));
        else assertThat(actual).doesNotContainKey("CVE-2026-1000");
        assertThat(service.findEpssScores(List.of("CVE-2026-1000"))).containsExactly(entry("CVE-2026-1000", 0.25));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,true", "7,true", "8,false", "40,false", "-1,false", "999,false"})
    void offlineDepsDevDoesNotRefreshVersionStatusFromStaleData(int age, boolean resolved) {
        String key = AirgappedSnapshotService.componentKey("NPM", "fixture", "1.0.0");
        entries.saveAndFlush(SnapshotEntry.builder().source("depsdev-version").entryKey(key)
                .payload("{\"licenses\":[\"MIT\"],\"advisoryKeys\":[\"GHSA-fixture\"],\"isDefault\":true,\"latestVersion\":\"2.0.0\"}").build());
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("depsdev-version")
                .recordCount(1).importedAt(java.time.LocalDateTime.now())
                .sourceAsOf(age == 999 ? null : java.time.LocalDate.now().minusDays(age)).build());
        var client = new com.salkcoding.oswl.client.DepsDevClient(service, true);
        var actual = client.getVersionsBatch(List.of(new com.salkcoding.oswl.client.DepsDevClient.ComponentKey(
                "NPM", "fixture", "1.0.0"))).getFirst();
        assertThat(actual.resolved()).isEqualTo(resolved);
        assertThat(actual.advisoryKeys()).containsExactly("GHSA-fixture");
        assertThat(actual.licenses()).containsExactly("MIT");
        assertThat(actual.latestVersion()).isEqualTo(resolved ? "2.0.0" : null);
        assertThat(actual.isDefault()).isEqualTo(resolved);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,false", "7,false", "8,true", "40,true", "-1,true", "999,true"})
    void offlineNvdRetainsFindingsWithoutConfirmingStaleCoverage(int age, boolean stale) {
        String key = AirgappedSnapshotService.componentKey("CONAN", "fixture", "1.0.0");
        String empty = AirgappedSnapshotService.componentKey("CONAN", "empty", "1.0.0");
        entries.saveAndFlush(SnapshotEntry.builder().source("nvd").entryKey(key)
                .payload("[{\"cveId\":\"CVE-2026-0001\",\"summary\":\"fixture\"}]").build());
        entries.saveAndFlush(SnapshotEntry.builder().source("nvd").entryKey(empty).payload("[]").build());
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("nvd")
                .recordCount(2).importedAt(java.time.LocalDateTime.now())
                .sourceAsOf(age == 999 ? null : java.time.LocalDate.now().minusDays(age)).build());
        var client = new com.salkcoding.oswl.client.NvdClient(service, true, null,
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
        var cpe = org.mockito.Mockito.mock(com.salkcoding.oswl.client.CpeMatchService.class);
        var source = new com.salkcoding.oswl.service.vulnerability.sources.NvdAdvisorySource(client, cpe);
        var stored = client.findSnapshotByComponentKeys(List.of(key, empty));
        assertThat(stored).containsKeys(key, empty);
        var actual = source.lookupSnapshot("fixture", "1.0.0", null, stored.get(key));
        assertThat(actual.lookupFailed()).isEqualTo(stale);
        assertThat(actual.findings()).singleElement().extracting(v -> v.cveId()).isEqualTo("CVE-2026-0001");
        assertThat(source.lookupSnapshot("empty", "1.0.0", null, stored.get(empty)).lookupFailed()).isEqualTo(stale);
        org.mockito.Mockito.verifyNoInteractions(cpe);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void nvdApplicabilityAndConfidenceSurviveExportImport(boolean missingConfigurations) throws Exception {
        String cpe = "cpe:2.3:a:fixture:product:1.0:*:*:*:*:*:*:*";
        String cveId = "CVE-2026-7654";
        String configurations = """
                ,"configurations":[{"operator":"OR","negate":false,"nodes":[
                  {"operator":"AND","negate":false,"cpeMatch":[{"vulnerable":false,"criteria":"%s","versionStartIncluding":"1.0","versionEndExcluding":"2.0"}]},
                  {"operator":"OR","negate":true,"cpeMatch":[{"vulnerable":false,"criteria":"%s"}]}
                ]}]
                """.formatted(cpe, cpe);
        String response = """
                {"startIndex":0,"totalResults":1,"resultsPerPage":1,"vulnerabilities":[{"cve":{
                  "id":"%s","sourceIdentifier":"fixture@nvd","lastModified":"2026-01-01T00:00:00Z",
                  "vulnStatus":"Analyzed","descriptions":[{"lang":"en","value":"fixture finding"}]%s
                }}]}
                """.formatted(cveId, missingConfigurations ? "" : configurations);
        var builder = org.springframework.web.client.RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo(
                        "https://services.nvd.nist.gov/rest/json/cves/2.0?cpeName="
                                + java.net.URLEncoder.encode(cpe, StandardCharsets.UTF_8)))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(
                        response, org.springframework.http.MediaType.APPLICATION_JSON));
        var online = new com.salkcoding.oswl.client.NvdClient();
        org.springframework.test.util.ReflectionTestUtils.setField(online, "restClient", builder.build());
        org.springframework.test.util.ReflectionTestUtils.setField(online, "minIntervalMs", 0L);
        var finding = online.findByCpeName(cpe, com.salkcoding.oswl.domain.enums.MatchConfidence.HIGH).getFirst();
        assertThat(finding.cveId()).isEqualTo(cveId);
        assertThat(finding.matchConfidence()).isEqualTo(com.salkcoding.oswl.domain.enums.MatchConfidence.HIGH);
        assertThat(finding.nvdApplicability()).isNotNull();
        if (missingConfigurations) assertThat(finding.nvdApplicability()).doesNotContain("configurations");
        else assertThat(finding.nvdApplicability()).contains("versionStartIncluding", "vulnerable");

        String name = "nvd-roundtrip-" + UUID.randomUUID();
        var library = libraries.saveAndFlush(com.salkcoding.oswl.domain.entity.vulnerability.Library.builder()
                .name(name).version("1.0.0").ecosystem("CONAN").build());
        cves.saveAndFlush(com.salkcoding.oswl.domain.entity.vulnerability.Cve.builder()
                .library(library).cveId(finding.cveId()).summary(finding.description())
                .matchConfidence(finding.matchConfidence()).nvdApplicability(finding.nvdApplicability())
                .sources(Set.of(com.salkcoding.oswl.domain.enums.CveSource.NVD,
                        com.salkcoding.oswl.domain.enums.CveSource.CPE)).build());
        library.recordLookupOutcomes(Map.of("NVD", "RESOLVED"));
        libraries.saveAndFlush(library);

        byte[] exported = service.exportBundle();
        service.importBundle(new ByteArrayInputStream(exported));
        String key = AirgappedSnapshotService.componentKey("CONAN", name, "1.0.0");
        var offline = new com.salkcoding.oswl.client.NvdClient(service, true, null,
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
        var lookup = offline.findSnapshotByComponentKeys(List.of(key)).get(key);
        assertThat(lookup).isNotNull();
        assertThat(lookup.complete()).isFalse(); // export has no verified NVD source date
        assertThat(lookup.findings()).singleElement().satisfies(actual -> {
            assertThat(actual.cveId()).isEqualTo(finding.cveId());
            assertThat(actual.matchConfidence()).isEqualTo(finding.matchConfidence());
            assertThat(actual.nvdApplicability()).isEqualTo(finding.nvdApplicability());
        });
        server.verify();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"{\"vulnStatus\":\"Rejected\"}", "[]", "true", "1", "\" \""})
    void nonTextNvdApplicabilityCannotBypassImportRollback(String applicability) {
        String oldKey = "CONAN|old-evidence|1.0.0";
        entries.saveAndFlush(SnapshotEntry.builder().source("nvd").entryKey(oldKey)
                .payload("[{\"cveId\":\"CVE-2026-7653\"}]").build());
        String line = "{\"ecosystem\":\"conan\",\"name\":\"fixture\",\"version\":\"1.0.0\","
                + "\"vulns\":[{\"cveId\":\"CVE-2026-7654\",\"nvdApplicability\":" + applicability + ","
                + "\"matchConfidence\":\"HIGH\"}]}";
        for (var mode : AirgappedSnapshotService.ImportMode.values()) {
            assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("nvd.jsonl", line))), mode))
                    .isInstanceOf(InvalidRequestException.class);
            assertThat(entries.countBySource("nvd")).isEqualTo(1);
            assertThat(service.findNvdVulns(List.of(oldKey)).get(oldKey)).singleElement()
                    .satisfies(v -> assertThat(v.cveId()).isEqualTo("CVE-2026-7653"));
            assertOldSource();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,false", "7,false", "8,true", "40,true", "-1,true", "999,true"})
    void offlineGithubRetainsFindingsButWithholdsStaleCoverageAndFixes(int age, boolean stale) {
        String key = AirgappedSnapshotService.componentKey("npm", "fixture", "1.0.0");
        String empty = AirgappedSnapshotService.componentKey("npm", "empty", "1.0.0");
        entries.saveAndFlush(SnapshotEntry.builder().source("github-advisory").entryKey(key)
                .payload("[{\"osvId\":\"GHSA-fixture\",\"fixVersion\":\"2.0.0\"}]").build());
        entries.saveAndFlush(SnapshotEntry.builder().source("github-advisory").entryKey(empty).payload("[]").build());
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("github-advisory")
                .recordCount(2).importedAt(java.time.LocalDateTime.now())
                .sourceAsOf(age == 999 ? null : java.time.LocalDate.now().minusDays(age)).build());
        var client = new com.salkcoding.oswl.client.GitHubAdvisoryClient(service, true, null,
                "https://api.github.com/graphql", java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
        var stored = client.findSnapshotByComponentKeys(List.of(key, empty));
        var source = new com.salkcoding.oswl.service.vulnerability.sources.GitHubAdvisorySource(client);
        assertThat(stored).containsKeys(key, empty);
        var actual = source.lookupSnapshot("NPM", "fixture", "1.0.0", stored.get(key));
        assertThat(actual.lookupFailed()).isEqualTo(stale);
        assertThat(actual.findings()).singleElement().extracting(v -> v.ghsaId()).isEqualTo("GHSA-fixture");
        assertThat(actual.findings().getFirst().fixVersion()).isEqualTo(stale ? null : "2.0.0");
        assertThat(source.lookupSnapshot("NPM", "empty", "1.0.0", stored.get(empty)).lookupFailed()).isEqualTo(stale);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource({"0,true", "7,true", "8,false", "40,false", "-1,false", "999,false"})
    void offlineOsvCoverageRequiresDatedRecentSource(int age, boolean resolved) {
        String emptyKey = AirgappedSnapshotService.componentKey("npm", "empty", "1.0.0");
        String affectedKey = AirgappedSnapshotService.componentKey("npm", "affected", "1.0.0");
        entries.saveAndFlush(SnapshotEntry.builder().source("osv").entryKey(emptyKey).payload("[]").build());
        entries.saveAndFlush(SnapshotEntry.builder().source("osv").entryKey(affectedKey)
                .payload("[{\"osvId\":\"GHSA-fixture\",\"fixVersion\":\"2.0.0\"}]").build());
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("osv")
                .recordCount(2).importedAt(java.time.LocalDateTime.now())
                .sourceAsOf(age == 999 ? null : java.time.LocalDate.now().minusDays(age)).build());
        var actual = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(
                new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "empty", "1.0.0"),
                new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "affected", "1.0.0")));
        assertThat(actual).allMatch(result -> result.resolved() == resolved);
        assertThat(actual.getFirst().vulns()).isEmpty();
        assertThat(actual.getLast().vulns()).singleElement().extracting(v -> v.osvId()).isEqualTo("GHSA-fixture");
        assertThat(actual.getLast().vulns().getFirst().fixVersion()).isEqualTo(resolved ? "2.0.0" : null);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void storedFutureSourceDatesCannotReportHealthyFreshness(boolean withValidSource) {
        var today = java.time.LocalDate.now();
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("osv")
                .recordCount(1).importedAt(java.time.LocalDateTime.now()).sourceAsOf(today.plusDays(10)).build());
        if (withValidSource) metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder()
                .source("epss").recordCount(1).importedAt(java.time.LocalDateTime.now()).sourceAsOf(today).build());
        assertThat(service.oldestSourceAsOf()).isNull();
        var indicator = new com.salkcoding.oswl.health.SnapshotFreshnessHealthIndicator(service);
        org.springframework.test.util.ReflectionTestUtils.setField(indicator, "airgappedEnabled", true);
        org.springframework.test.util.ReflectionTestUtils.setField(indicator, "stalenessWarnDays", 7);
        org.springframework.test.util.ReflectionTestUtils.setField(indicator, "stalenessCriticalDays", 30);
        assertThat(indicator.health().getStatus().getCode()).isEqualTo("DOWN");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "false", "{}", "[null]", "[1]", "[\"\"]"})
    void malformedFixConflictsRollBackTheImport(String candidates) throws Exception {
        String record = "{\"ecosystem\":\"npm\",\"name\":\"fixture\",\"version\":\"1.0.0\",\"vulns\":[" +
                "{\"osvId\":\"GHSA-fixture\",\"fixVersionConflictCandidates\":" + candidates + "}]}";
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("osv.jsonl", record)))))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(service.findEpssScores(List.of("CVE-2026-9000"))).containsEntry("CVE-2026-9000", 0.25);
        String key = AirgappedSnapshotService.componentKey("npm", "fixture", "1.0.0");
        entries.save(SnapshotEntry.builder().source("osv").entryKey(key).payload(
                "[{\"osvId\":\"GHSA-fixture\",\"fixVersionConflictCandidates\":" + candidates + "}]").build());
        assertThat(service.findOsvVulns(List.of(key))).doesNotContainKey(key);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, 8, 999})
    void githubRangeFixSurvivesDatabaseImportWithExplicitFreshness(int age) throws Exception {
        var builder = org.springframework.web.client.RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var live = new com.salkcoding.oswl.client.GitHubAdvisoryClient(null, false, "fixture", "https://api.github.com",
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
        org.springframework.test.util.ReflectionTestUtils.setField(live, "restClient", builder.build());
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var nodes = new ArrayList<Map<String, Object>>();
        for (String[] interval : List.of(new String[]{"< 2.0.0", "2.0.0"}, new String[]{">= 2.0.0, < 3.0.0", "3.0.0"})) {
            nodes.add(Map.of("package", Map.of("name", "fixture", "ecosystem", "NPM"),
                    "vulnerableVersionRange", interval[0], "firstPatchedVersion", Map.of("identifier", interval[1]),
                    "severity", "HIGH", "advisory", Map.of("identifiers", List.of(
                            Map.of("type", "GHSA", "value", "GHSA-fixture"), Map.of("type", "CVE", "value", "CVE-2026-123450")))));
        }
        String response = json.writeValueAsString(Map.of("data", Map.of("securityVulnerabilities",
                Map.of("pageInfo", Map.of("hasNextPage", false), "nodes", nodes))));
        server.expect(org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo("https://api.github.com/graphql"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess(response,
                        org.springframework.http.MediaType.APPLICATION_JSON));
        var online = live.findByPackage("npm", "fixture", "1.0.0");
        assertThat(online).singleElement().satisfies(v -> assertThat(v.fixVersion()).isEqualTo("3.0.0"));
        var finding = online.getFirst();
        var stored = new AirgappedSnapshotService.SnapshotVuln(finding.ghsaId(), finding.cveId(), finding.summary(),
                finding.fixVersion(), null, finding.severity().name(), finding.cvssScore(), finding.cvss3Vector(), null,
                finding.fixVersionConflictCandidates());
        String line = json.writeValueAsString(Map.of("ecosystem", "NPM", "name", "fixture", "version", "1.0.0", "vulns", List.of(stored)));
        // These dates simulate bundle freshness; they are not provider revision dates.
        Map<String, Object> date = age == 999 ? Map.of() : Map.of("asOf", java.time.LocalDate.now().minusDays(age).toString());
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(line.getBytes(StandardCharsets.UTF_8)));
        String manifest = json.writeValueAsString(Map.of("formatVersion", 2, "sources", Map.of("github-advisory", date),
                "files", Map.of("github-advisory.jsonl", Map.of("sha256", hash, "lines", 1))));
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("github-advisory.jsonl", line, "meta.json", manifest))));
        String key = AirgappedSnapshotService.componentKey("NPM", "fixture", "1.0.0");
        String absent = AirgappedSnapshotService.componentKey("NPM", "fixture", "1.0.1");
        var offline = new com.salkcoding.oswl.client.GitHubAdvisoryClient(service, true, null, null,
                java.time.Duration.ofSeconds(1), java.time.Duration.ofSeconds(1));
        var lookups = offline.findSnapshotByComponentKeys(List.of(key, absent));
        assertThat(lookups).doesNotContainKey(absent);
        var result = new com.salkcoding.oswl.service.vulnerability.sources.GitHubAdvisorySource(offline)
                .lookupSnapshot("NPM", "fixture", "1.0.0", lookups.get(key));
        assertThat(result.lookupFailed()).isEqualTo(age != 0);
        assertThat(result.findings()).singleElement().satisfies(v -> {
            assertThat(v.ghsaId()).isEqualTo(finding.ghsaId());
            assertThat(v.cveId()).isEqualTo(finding.cveId());
            assertThat(v.severity()).isEqualTo(finding.severity());
            assertThat(v.fixVersion()).isEqualTo(age == 0 ? "3.0.0" : null);
        });
        assertThat(service.findGitHubAdvisoryVulns(List.of(key)).get(key).getFirst().fixVersion()).isEqualTo("3.0.0");
        assertThat(metadata.findById("github-advisory").orElseThrow().getSourceAsOf())
                .isEqualTo(age == 999 ? null : java.time.LocalDate.now().minusDays(age));
        server.verify();
    }

    @Autowired AirgappedSnapshotService service;
    @Autowired SnapshotEntryRepository entries;
    @Autowired SnapshotMetaRepository metadata;
    @Autowired DataSource dataSource;

    @BeforeEach void existingSource() {
        entries.deleteAllInBatch();
        metadata.deleteAllInBatch();
        entries.save(SnapshotEntry.builder().source("epss").entryKey("CVE-2026-9000").payload("0.25").build());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "false", "{}", "[null]", "[1]", "[{}]"})
    void malformedVulnerabilityListsCannotReplaceExistingFindings(String vulns) throws Exception {
        for (String source : List.of("osv", "github-advisory", "nvd")) {
            String key = "NPM|fixture|1.0.0";
            String old = "[{\"osvId\":\"GHSA-old\",\"fixVersion\":\"2.0.0\"}]";
            entries.saveAndFlush(SnapshotEntry.builder().source(source).entryKey(key).payload(old).build());
            String line = "{\"ecosystem\":\"npm\",\"name\":\"fixture\",\"version\":\"1.0.0\",\"vulns\":" + vulns + "}";
            assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of(source + ".jsonl", line)))))
                    .isInstanceOf(InvalidRequestException.class);
            assertThat(entries.findBySourceAndEntryKeyIn(source, List.of(key))).singleElement()
                    .extracting(SnapshotEntry::getPayload).isEqualTo(old);
        }
    }

    @Test void missingVulnsIsRejectedButExplicitEmptyAndUnresolvedRecordsRemainValid() throws Exception {
        String identity = "\"ecosystem\":\"npm\",\"name\":\"fixture\",\"version\":\"1.0.0\"";
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("osv.jsonl", "{" + identity + "}")))))
                .isInstanceOf(InvalidRequestException.class);
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("osv.jsonl", "{" + identity + ",\"vulns\":[]}",
                "unresolved.jsonl", "{" + identity + "}"))));
        assertThat(service.findOsvVulns(List.of("NPM|fixture|1.0.0"))).containsEntry("NPM|fixture|1.0.0", List.of());
        assertThat(service.findUnresolvedKeys(List.of("NPM|fixture|1.0.0"))).containsExactly("NPM|fixture|1.0.0");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "[null]", "[{}]", "false", "{}"})
    void corruptStoredVulnerabilitiesRemainUnresolved(String payload) {
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("osv")
                .recordCount(2).importedAt(java.time.LocalDateTime.now()).sourceAsOf(java.time.LocalDate.now()).build());
        String key = "NPM|fixture|1.0.0";
        for (String source : List.of("osv", "github-advisory", "nvd")) {
            entries.saveAndFlush(SnapshotEntry.builder().source(source).entryKey(key).payload(payload).build());
            entries.saveAndFlush(SnapshotEntry.builder().source(source).entryKey("NPM|clean|1.0.0").payload("[]").build());
        }
        var keys = List.of(key, "NPM|clean|1.0.0");
        assertThat(service.findOsvVulns(keys)).containsOnly(entry("NPM|clean|1.0.0", List.of()));
        assertThat(service.findGitHubAdvisoryVulns(keys)).containsOnly(entry("NPM|clean|1.0.0", List.of()));
        assertThat(service.findNvdVulns(keys)).containsOnly(entry("NPM|clean|1.0.0", List.of()));
        var results = new com.salkcoding.oswl.client.OsvClient(service, true).queryBatch(List.of(
                new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "fixture", "1.0.0"),
                new com.salkcoding.oswl.client.OsvClient.OsvQuery("npm", "clean", "1.0.0")));
        assertThat(results.getFirst().resolved()).isFalse();
        assertThat(results.get(1).resolved()).isTrue();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"broken", "null", "[]", "{\"formatVersion\":4}",
            "{\"formatVersion\":0}", "{\"formatVersion\":null}", "{\"formatVersion\":\"2\"}",
            "{\"formatVersion\":2.5}", "{\"formatVersion\":2}",
            "{\"formatVersion\":2,\"files\":{}}", "{\"formatVersion\":2,\"files\":{\"epss.jsonl\":{}}}"})
    void invalidMetadataCannotDowngradeOrBypassIntegrity(String meta) throws Exception {
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of(
                "meta.json", meta, "epss.jsonl", "{\"cveId\":\"CVE-2026-9001\",\"score\":0.8}")))))
                .isInstanceOf(InvalidRequestException.class);
        assertOldSource();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing-declared", "unknown-entry", "nested-entry"})
    void versionTwoFileInventoryMustMatchTheArchive(String mismatch) throws Exception {
        String line = "{\"cveId\":\"CVE-2026-9001\",\"score\":0.8}";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(line.getBytes(StandardCharsets.UTF_8)));
        String manifestFiles = "\"epss.jsonl\":{\"sha256\":\"" + hash + "\",\"lines\":1}";
        if (mismatch.equals("missing-declared")) manifestFiles += ",\"osv.jsonl\":{\"sha256\":\"" + hash + "\",\"lines\":1}";
        var files = new LinkedHashMap<String, String>();
        files.put("meta.json", "{\"formatVersion\":2,\"files\":{" + manifestFiles + "}}");
        files.put(mismatch.equals("nested-entry") ? "nested/epss.jsonl" : "epss.jsonl", line);
        if (mismatch.equals("unknown-entry")) files.put("typo-osv.jsonl", line);
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(files))))
                .isInstanceOf(InvalidRequestException.class);
        assertOldSource();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "-1", "1.5", "\"1\"", "2147483648", "0", "2"})
    void declaredLineCountMustBeValidAndMatchContent(String lines) throws Exception {
        String line = "{\"cveId\":\"CVE-2026-9001\",\"score\":0.8}";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(line.getBytes(StandardCharsets.UTF_8)));
        String meta = "{\"formatVersion\":2,\"files\":{\"epss.jsonl\":{\"sha256\":\"" + hash + "\",\"lines\":" + lines + "}}}";
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("meta.json", meta, "epss.jsonl", line)))))
                .isInstanceOf(InvalidRequestException.class);
        assertOldSource();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.CsvSource(value = {"2024-01-01;2026-01-01;MERGE;2024-01-01",
            "null;2026-01-01;MERGE;null", "2024-01-01;null;MERGE;null",
            "2026-01-01;2024-01-01;MERGE;2024-01-01", "2024-01-01;2026-01-01;REPLACE;2026-01-01"}, delimiter = ';', nullValues = "null")
    void mergingNewDataCannotRefreshRetainedOlderRecords(String oldDate, String newDate, String mode, String expected) throws Exception {
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("epss")
                .recordCount(1).importedAt(java.time.LocalDateTime.now())
                .sourceAsOf(oldDate == null ? null : java.time.LocalDate.parse(oldDate)).build());
        String line = "{\"cveId\":\"CVE-2026-9001\",\"score\":0.8}";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(line.getBytes(StandardCharsets.UTF_8)));
        String source = newDate == null ? "{}" : "{\"asOf\":\"" + newDate + "\"}";
        String meta = "{\"formatVersion\":2,\"sources\":{\"epss\":" + source
                + "},\"files\":{\"epss.jsonl\":{\"sha256\":\"" + hash + "\",\"lines\":1}}}";
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("meta.json", meta, "epss.jsonl", line))),
                AirgappedSnapshotService.ImportMode.valueOf(mode));
        assertThat(metadata.findById("epss").orElseThrow().getSourceAsOf())
                .isEqualTo(expected == null ? null : java.time.LocalDate.parse(expected));
        assertThat(service.findEpssScores(List.of("CVE-2026-9000", "CVE-2026-9001"))).hasSize(mode.equals("MERGE") ? 2 : 1);
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("osv")
                .recordCount(0).importedAt(java.time.LocalDateTime.now()).sourceAsOf(java.time.LocalDate.of(2026, 1, 1)).build());
        assertThat(service.oldestSourceAsOf()).isEqualTo(expected == null ? null : java.time.LocalDate.parse(expected));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"\"not-a-date\"", "\"2026-02-30\"", "\"2099-01-01\"", "123", "{}", "\"\""})
    void invalidSourceDatesCannotEstablishFreshness(String date) throws Exception {
        String line = "{\"cveId\":\"CVE-2026-9001\",\"score\":0.8}";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(line.getBytes(StandardCharsets.UTF_8)));
        String meta = "{\"formatVersion\":2,\"sources\":{\"epss\":{\"asOf\":" + date
                + "}},\"files\":{\"epss.jsonl\":{\"sha256\":\"" + hash + "\",\"lines\":1}}}";
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("meta.json", meta, "epss.jsonl", line)))))
                .isInstanceOf(InvalidRequestException.class);
        assertOldSource();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CVE-2026-1234", " cve-2026-1234 ", "CVE-2026-1234567"})
    void validEpssIdentitiesCanBeImportedAndDeleted(String identity) throws Exception {
        String key = identity.strip().toUpperCase(java.util.Locale.ROOT);
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("epss.jsonl",
                "{\"cveId\":\"" + identity + "\",\"score\":0}"))));
        assertThat(service.findEpssScores(List.of(key))).containsOnly(entry(key, 0.0));
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("epss.jsonl",
                "{\"cveId\":\"" + identity + "\",\"_deleted\":true}"))), AirgappedSnapshotService.ImportMode.MERGE);
        assertThat(entries.countBySource("epss")).isZero();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"CVE-2026-1", "CVE-26-1234", "GHSA-abcd", "CVE-2026-1234-extra"})
    void invalidEpssIdentitiesPreserveExistingEvidence(String identity) throws Exception {
        byte[] original = bundle(Map.of("epss.jsonl", "{\"cveId\":\"CVE-2026-1000\",\"score\":0.25}"));
        service.importBundle(new ByteArrayInputStream(original));
        var originalMeta = metadata.findById("epss").orElseThrow();
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("epss").recordCount(1)
                .importedAt(originalMeta.getImportedAt()).sourceAsOf(java.time.LocalDate.of(2026, 1, 1)).build());
        var before = service.status();
        for (var mode : AirgappedSnapshotService.ImportMode.values()) {
            for (boolean deleted : List.of(false, true)) {
                String line = "{\"cveId\":\"" + identity + "\",\"score\":0.8,\"_deleted\":" + deleted + "}";
                assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("epss.jsonl", line))), mode))
                        .isInstanceOf(InvalidRequestException.class);
                assertThat(service.findEpssScores(List.of("CVE-2026-1000"))).containsOnly(entry("CVE-2026-1000", 0.25));
                assertThat(service.status()).isEqualTo(before);
            }
        }
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "\"0.5\"", "-0.01", "1.01", "1e999", "{}"})
    void invalidEpssScoresDoNotReplaceExistingData(String score) throws Exception {
        String line = "{\"cveId\":\"CVE-2026-9001\",\"score\":" + score + "}";
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("epss.jsonl", line)))))
                .isInstanceOf(InvalidRequestException.class);
        assertOldSource();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"NaN", "Infinity", "-Infinity", "-0.01", "1.01"})
    void invalidStoredEpssScoresAreNotReturned(String score) {
        entries.saveAndFlush(SnapshotEntry.builder().source("epss").entryKey("CVE-2026-9004").payload(score).build());
        assertThat(service.findEpssScores(List.of("CVE-2026-9000", "CVE-2026-9004"))).containsOnly(entry("CVE-2026-9000", 0.25));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles = {0, 0.5, 1})
    void validEpssProbabilitiesRoundTrip(double score) throws Exception {
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("epss.jsonl",
                "{\"cveId\":\"CVE-2026-9001\",\"score\":" + score + "}"))));
        assertThat(service.findEpssScores(List.of("CVE-2026-9001"))).containsOnly(entry("CVE-2026-9001", score));
    }

    @Test void importsMultipleChunksAndCleansStagingFiles() throws Exception {
        Set<Path> before = stagedFiles();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 5000; i++) lines.append("{\"cveId\":\"CVE-2026-").append(10000 + i).append("\",\"score\":0.75}\n");
        byte[] zip = bundle(Map.of("epss.jsonl", lines.toString()));
        long start = System.nanoTime();
        var result = service.importBundle(new ByteArrayInputStream(zip));
        assertThat(result.totalRecords()).isEqualTo(5000);
        assertThat(entries.countBySource("epss")).isEqualTo(5000);
        assertThat(service.findEpssScores(List.of("CVE-2026-9000", "CVE-2026-14999"))).containsOnly(entry("CVE-2026-14999", .75));
        assertThat(stagedFiles()).isEqualTo(before);
        Path report = Path.of("build/reports/performance/snapshot.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "5000 records, chunk=500, elapsed_ms=" + (System.nanoTime()-start)/1_000_000 + "\n");
    }

    @Test void checksumAtEndRejectsBeforeReplaceAndCleansFiles() throws Exception {
        Set<Path> before = stagedFiles();
        Map<String, String> files = new LinkedHashMap<>();
        files.put("epss.jsonl", "{\"cveId\":\"CVE-2026-9001\",\"score\":0.8}\n");
        files.put("meta.json", "{\"formatVersion\":2,\"files\":{\"epss.jsonl\":{\"sha256\":\"wrong\",\"lines\":1}}}");
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(files))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("integrity");
        assertOldSource();
        assertThat(stagedFiles()).isEqualTo(before);
    }

    @Test void interruptedUploadDoesNotAcquireConnectionOrMutateSource() throws Exception {
        Set<Path> before = stagedFiles();
        byte[] zip = bundle(Map.of("epss.jsonl", "{\"cveId\":\"CVE-2026-9001\",\"score\":0.8}\n"));
        int activeBefore = ((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections();
        InputStream input = new ByteArrayInputStream(zip) {
            @Override public synchronized int read(byte[] b, int off, int len) {
                assertThat(((HikariDataSource) dataSource).getHikariPoolMXBean().getActiveConnections()).isEqualTo(activeBefore);
                Thread.currentThread().interrupt();
                return super.read(b, off, len);
            }
        };
        try {
            assertThatThrownBy(() -> service.importBundle(input)).isInstanceOf(InvalidRequestException.class);
        } finally { Thread.interrupted(); }
        assertOldSource();
        assertThat(stagedFiles()).isEqualTo(before);
    }

    @Test void databaseFailureAfterFirstChunkRollsBackReplace() throws Exception {
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 600; i++) lines.append("{\"cveId\":\"CVE-2026-").append(10000 + i).append("\",\"score\":0.75}\n");
        // Exceeds entry_key length after the first chunk has already been flushed.
        lines.append("{\"cveId\":\"").append("CVE-2026-" + "1".repeat(2000)).append("\",\"score\":0.8}\n");
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("epss.jsonl",lines.toString())))))
                .isInstanceOf(RuntimeException.class);
        assertOldSource();
    }

    private void assertOldSource() {
        assertThat(entries.countBySource("epss")).isEqualTo(1);
        assertThat(service.findEpssScores(List.of("CVE-2026-9000"))).containsEntry("CVE-2026-9000", .25);
        assertThat(metadata.count()).isZero();
    }

    private Set<Path> stagedFiles() throws IOException {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(p -> p.getFileName().toString().startsWith("oswl-snapshot-")).collect(java.util.stream.Collectors.toSet());
        }
    }

    private static byte[] versionedBundle(Map<String, String> content, String id, String base) throws Exception {
        Map<String, String> files = new LinkedHashMap<>(content);
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        var meta = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(files.getOrDefault("meta.json", "{}"));
        files.remove("meta.json");
        meta.put("formatVersion", 2);
        meta.put("mode", base == null ? "full" : "delta");
        meta.put("bundleId", id);
        if (base != null) meta.put("basedOnBundleId", base);
        var manifest = meta.putObject("files");
        for (var file : files.entrySet()) {
            var entry = manifest.putObject(file.getKey());
            entry.put("sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(file.getValue().getBytes(StandardCharsets.UTF_8))));
            entry.put("lines", file.getValue().lines().filter(line -> !line.isBlank()).count());
        }
        files.put("meta.json", mapper.writeValueAsString(meta));
        return bundle(files);
    }

    private static byte[] bundle(Map<String, String> files) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            for (var file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey()));
                zip.write(file.getValue().getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
