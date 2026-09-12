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

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:snapshot-budget;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS TEXT")
class SnapshotImportTransactionTest {

    @Autowired com.salkcoding.oswl.repository.vulnerability.LibraryRepository libraries;
    @Autowired com.salkcoding.oswl.repository.vulnerability.CveRepository cves;

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
                "epss.jsonl", "{\"cveId\":\"CVE-KEPT\",\"score\":0.4}"))));
        String more = json.writeValueAsString(Map.of("dataNotices", Map.of("credit", "different-" + credit)));
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("meta.json", more,
                "epss.jsonl", "{\"cveId\":\"CVE-REJECTED\",\"score\":0.6}"))), AirgappedSnapshotService.ImportMode.MERGE))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("512 KiB");
        assertThat(service.findEpssScores(List.of("CVE-KEPT", "CVE-REJECTED"))).containsOnlyKeys("CVE-KEPT");
        assertThat(exportedMeta(service.exportBundle()).path("upstreamDataNotices").get(0).path("credit").asText()).isEqualTo(credit);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"\"dataNotices\":null", "\"dataNotices\":[]",
            "\"dataNotices\":\"notice\"", "\"upstreamDataNotices\":{}", "\"upstreamDataNotices\":[null]",
            "\"upstreamDataNotices\":[\"notice\"]"})
    void invalidNoticesCannotEraseExistingSourceData(String notice) throws Exception {
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("meta.json", "{" + notice + "}",
                "epss.jsonl", "{\"cveId\":\"CVE-NEW\",\"score\":0.4}")))))
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
                    "{\"cveId\":\"CVE-" + owner + "\",\"score\":0.4}"))), AirgappedSnapshotService.ImportMode.valueOf(mode));
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
        assertThat(service.findEpssScores(List.of("CVE-OLD"))).containsEntry("CVE-OLD", 0.25);
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
        assertThat(entries.findAll()).singleElement().satisfies(row -> assertThat(row.getEntryKey()).isEqualTo("CVE-OLD"));
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
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("epss")
                .recordCount(1).importedAt(java.time.LocalDateTime.now())
                .sourceAsOf(age == 999 ? null : java.time.LocalDate.now().minusDays(age)).build());
        var actual = new com.salkcoding.oswl.client.EpssClient(service, true).fetchScores(List.of("CVE-OLD"));
        if (current) assertThat(actual).containsExactly(entry("CVE-OLD", 0.25));
        else assertThat(actual).doesNotContainKey("CVE-OLD");
        assertThat(service.findEpssScores(List.of("CVE-OLD"))).containsExactly(entry("CVE-OLD", 0.25));
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
        assertThat(service.findEpssScores(List.of("CVE-OLD"))).containsEntry("CVE-OLD", 0.25);
        String key = AirgappedSnapshotService.componentKey("npm", "fixture", "1.0.0");
        entries.save(SnapshotEntry.builder().source("osv").entryKey(key).payload(
                "[{\"osvId\":\"GHSA-fixture\",\"fixVersionConflictCandidates\":" + candidates + "}]").build());
        assertThat(service.findOsvVulns(List.of(key))).doesNotContainKey(key);
    }
    @Autowired AirgappedSnapshotService service;
    @Autowired SnapshotEntryRepository entries;
    @Autowired SnapshotMetaRepository metadata;
    @Autowired DataSource dataSource;

    @BeforeEach void existingSource() {
        entries.deleteAllInBatch();
        metadata.deleteAllInBatch();
        entries.save(SnapshotEntry.builder().source("epss").entryKey("CVE-OLD").payload("0.25").build());
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
                "meta.json", meta, "epss.jsonl", "{\"cveId\":\"CVE-NEW\",\"score\":0.8}")))))
                .isInstanceOf(InvalidRequestException.class);
        assertOldSource();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"missing-declared", "unknown-entry", "nested-entry"})
    void versionTwoFileInventoryMustMatchTheArchive(String mismatch) throws Exception {
        String line = "{\"cveId\":\"CVE-NEW\",\"score\":0.8}";
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
        String line = "{\"cveId\":\"CVE-NEW\",\"score\":0.8}";
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
        String line = "{\"cveId\":\"CVE-NEW\",\"score\":0.8}";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(line.getBytes(StandardCharsets.UTF_8)));
        String source = newDate == null ? "{}" : "{\"asOf\":\"" + newDate + "\"}";
        String meta = "{\"formatVersion\":2,\"sources\":{\"epss\":" + source
                + "},\"files\":{\"epss.jsonl\":{\"sha256\":\"" + hash + "\",\"lines\":1}}}";
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("meta.json", meta, "epss.jsonl", line))),
                AirgappedSnapshotService.ImportMode.valueOf(mode));
        assertThat(metadata.findById("epss").orElseThrow().getSourceAsOf())
                .isEqualTo(expected == null ? null : java.time.LocalDate.parse(expected));
        assertThat(service.findEpssScores(List.of("CVE-OLD", "CVE-NEW"))).hasSize(mode.equals("MERGE") ? 2 : 1);
        metadata.saveAndFlush(com.salkcoding.oswl.domain.entity.snapshot.SnapshotMeta.builder().source("osv")
                .recordCount(0).importedAt(java.time.LocalDateTime.now()).sourceAsOf(java.time.LocalDate.of(2026, 1, 1)).build());
        assertThat(service.oldestSourceAsOf()).isEqualTo(expected == null ? null : java.time.LocalDate.parse(expected));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"\"not-a-date\"", "\"2026-02-30\"", "\"2099-01-01\"", "123", "{}", "\"\""})
    void invalidSourceDatesCannotEstablishFreshness(String date) throws Exception {
        String line = "{\"cveId\":\"CVE-NEW\",\"score\":0.8}";
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(line.getBytes(StandardCharsets.UTF_8)));
        String meta = "{\"formatVersion\":2,\"sources\":{\"epss\":{\"asOf\":" + date
                + "}},\"files\":{\"epss.jsonl\":{\"sha256\":\"" + hash + "\",\"lines\":1}}}";
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("meta.json", meta, "epss.jsonl", line)))))
                .isInstanceOf(InvalidRequestException.class);
        assertOldSource();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"null", "\"0.5\"", "-0.01", "1.01", "1e999", "{}"})
    void invalidEpssScoresDoNotReplaceExistingData(String score) throws Exception {
        String line = "{\"cveId\":\"CVE-NEW\",\"score\":" + score + "}";
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("epss.jsonl", line)))))
                .isInstanceOf(InvalidRequestException.class);
        assertOldSource();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"NaN", "Infinity", "-Infinity", "-0.01", "1.01"})
    void invalidStoredEpssScoresAreNotReturned(String score) {
        entries.saveAndFlush(SnapshotEntry.builder().source("epss").entryKey("CVE-INVALID").payload(score).build());
        assertThat(service.findEpssScores(List.of("CVE-OLD", "CVE-INVALID"))).containsOnly(entry("CVE-OLD", 0.25));
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(doubles = {0, 0.5, 1})
    void validEpssProbabilitiesRoundTrip(double score) throws Exception {
        service.importBundle(new ByteArrayInputStream(bundle(Map.of("epss.jsonl",
                "{\"cveId\":\"CVE-NEW\",\"score\":" + score + "}"))));
        assertThat(service.findEpssScores(List.of("CVE-NEW"))).containsOnly(entry("CVE-NEW", score));
    }

    @Test void importsMultipleChunksAndCleansStagingFiles() throws Exception {
        Set<Path> before = stagedFiles();
        StringBuilder lines = new StringBuilder();
        for (int i = 0; i < 5000; i++) lines.append("{\"cveId\":\"CVE-NEW-").append(i).append("\",\"score\":0.75}\n");
        byte[] zip = bundle(Map.of("epss.jsonl", lines.toString()));
        long start = System.nanoTime();
        var result = service.importBundle(new ByteArrayInputStream(zip));
        assertThat(result.totalRecords()).isEqualTo(5000);
        assertThat(entries.countBySource("epss")).isEqualTo(5000);
        assertThat(service.findEpssScores(List.of("CVE-OLD", "CVE-NEW-4999"))).containsOnly(entry("CVE-NEW-4999", .75));
        assertThat(stagedFiles()).isEqualTo(before);
        Path report = Path.of("build/reports/performance/snapshot.txt");
        Files.createDirectories(report.getParent());
        Files.writeString(report, "5000 records, chunk=500, elapsed_ms=" + (System.nanoTime()-start)/1_000_000 + "\n");
    }

    @Test void checksumAtEndRejectsBeforeReplaceAndCleansFiles() throws Exception {
        Set<Path> before = stagedFiles();
        Map<String, String> files = new LinkedHashMap<>();
        files.put("epss.jsonl", "{\"cveId\":\"CVE-NEW\",\"score\":0.8}\n");
        files.put("meta.json", "{\"formatVersion\":2,\"files\":{\"epss.jsonl\":{\"sha256\":\"wrong\",\"lines\":1}}}");
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(files))))
                .isInstanceOf(InvalidRequestException.class).hasMessageContaining("integrity");
        assertOldSource();
        assertThat(stagedFiles()).isEqualTo(before);
    }

    @Test void interruptedUploadDoesNotAcquireConnectionOrMutateSource() throws Exception {
        Set<Path> before = stagedFiles();
        byte[] zip = bundle(Map.of("epss.jsonl", "{\"cveId\":\"CVE-NEW\",\"score\":0.8}\n"));
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
        for (int i = 0; i < 600; i++) lines.append("{\"cveId\":\"CVE-NEW-").append(i).append("\",\"score\":0.75}\n");
        // Exceeds entry_key length after the first chunk has already been flushed.
        lines.append("{\"cveId\":\"").append("X".repeat(2000)).append("\",\"score\":0.8}\n");
        assertThatThrownBy(() -> service.importBundle(new ByteArrayInputStream(bundle(Map.of("epss.jsonl",lines.toString())))))
                .isInstanceOf(RuntimeException.class);
        assertOldSource();
    }

    private void assertOldSource() {
        assertThat(entries.countBySource("epss")).isEqualTo(1);
        assertThat(service.findEpssScores(List.of("CVE-OLD"))).containsEntry("CVE-OLD", .25);
        assertThat(metadata.count()).isZero();
    }

    private Set<Path> stagedFiles() throws IOException {
        try (var paths = Files.list(Path.of(System.getProperty("java.io.tmpdir")))) {
            return paths.filter(p -> p.getFileName().toString().startsWith("oswl-snapshot-")).collect(java.util.stream.Collectors.toSet());
        }
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
