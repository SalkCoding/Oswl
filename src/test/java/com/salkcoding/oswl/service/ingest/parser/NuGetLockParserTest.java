package com.salkcoding.oswl.service.ingest.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.service.ingest.CondaPypiMappingService;
import com.salkcoding.oswl.service.ingest.DependencyManifestParserService;
import com.salkcoding.oswl.service.ingest.MavenBomVersionResolver;
import com.salkcoding.oswl.vdb.OsvFixVersionSelector;
import com.salkcoding.oswl.vdb.OsvRangeEvaluator;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class NuGetLockParserTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void lockTargetsAndSourceFilesSurviveDeduplication(boolean reverse, @TempDir Path directory) throws Exception {
        var mapper = new ObjectMapper();
        var targets = new LinkedHashMap<String, Object>();
        var names = reverse ? List.of("net8.0/win-x64", "net8.0") : List.of("net8.0", "net8.0/win-x64");
        for (String target : names) targets.put(target, Map.of("Fixture", Map.of(
                "resolved", "1.0.0", "type", target.contains("/") ? "Transitive" : "Direct")));
        Files.createDirectories(directory.resolve("first"));
        Files.createDirectories(directory.resolve("second"));
        Files.writeString(directory.resolve("first/packages.lock.json"), mapper.writeValueAsString(
                Map.of("version", 1, "dependencies", targets)));
        Files.writeString(directory.resolve("second/packages.lock.json"), mapper.writeValueAsString(
                Map.of("version", 1, "dependencies", Map.of("net9.0/linux-x64", Map.of("Fixture",
                        Map.of("resolved", "1.0.0", "type", "Transitive"))))));
        var parser = new DependencyManifestParserService(mock(MavenBomVersionResolver.class), mock(CondaPypiMappingService.class));
        var parsed = parser.parseDependencies(directory, "fixture");
        assertThat(parsed.components()).singleElement().satisfies(component -> {
            assertThat(component.getName()).isEqualTo("Fixture");
            assertThat(component.getVersion()).isEqualTo("1.0.0");
            assertThat(component.getDependencyInfo()).contains("first/packages.lock.json", "second/packages.lock.json",
                    "net8.0", "net8.0/win-x64", "net9.0/linux-x64", "Direct", "Transitive");
            assertThat(component.getDependencyInfo().lines().count()).isEqualTo(3);
            assertThat(component.getDependencyPaths()).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"dependencies\":{\"net8.0\":{\"Fixture\":{\"resolved\":\"8.0.3\",\"resolved\":\"8.0.4\"}}}}",
            "{\"dependencies\":{\"net8.0\":{\"Fixture\":{\"resolved\":\"8.0.3\"},\"Fixture\":{\"resolved\":\"8.0.4\"}}}}",
            "{\"dependencies\":{\"net8.0\":{\"Fixture\":{\"resolved\":\"8.0.3\"}},\"net8.0\":{}}}",
            "{\"dependencies\":{\"net8.0\":{\"Fixture\":{\"resolved\":\"8.0.3\"}}},\"dependencies\":{}}",
            "{\"dependencies\":{}} {\"dependencies\":{\"net8.0\":{\"Fixture\":{\"resolved\":\"8.0.3\"}}}}",
            "{\"dependencies\":{}} trailing-data"})
    void ambiguousJsonCannotEraseAnInstalledVersion(String json, @TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("packages.lock.json"), json);
        var service = new DependencyManifestParserService(mock(MavenBomVersionResolver.class), mock(CondaPypiMappingService.class));
        assertThatThrownBy(() -> service.parseDependencies(directory, "fixture"))
                .isInstanceOf(com.salkcoding.oswl.exception.InvalidRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.*", "[1,2)", "[1.0.0]", ">=1.0.0", "1..0", "1.0.0.0.0",
            "2147483648.0.0", "1.0.0-01", "1.0.0-alpha.01", "1.0.0+", "v1.0.0"})
    void resolvedMustBeAConcreteNugetVersion(String version, @TempDir Path directory) throws Exception {
        writeResolved(directory, version);
        var service = new DependencyManifestParserService(mock(MavenBomVersionResolver.class), mock(CondaPypiMappingService.class));
        assertThatThrownBy(() -> service.parseDependencies(directory, "fixture"))
                .isInstanceOf(com.salkcoding.oswl.exception.InvalidRequestException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"08.0.03.0", "1.0", "1.0.0-alpha.10", "1.0.0+build", "1.0.0.1"})
    void concreteResolvedVersionsKeepTheirOriginalEvidence(String version, @TempDir Path directory) throws Exception {
        writeResolved(directory, version);
        var components = new NugetManifestParser().parseNuGetLockFile(directory, "fixture");
        assertThat(components).singleElement().extracting(ScanPayload.ComponentPayload::getVersion).isEqualTo(version);
    }

    private static void writeResolved(Path directory, String version) throws Exception {
        Files.writeString(directory.resolve("packages.lock.json"), new ObjectMapper().writeValueAsString(
                Map.of("version", 1, "dependencies", Map.of("net8.0", Map.of("Fixture",
                        Map.of("type", "Direct", "requested", "[1.0.0, )", "resolved", version))))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"{", "null", "[]", "{}", "{\"dependencies\":[]}",
            "{\"dependencies\":{\"net8.0\":[]}}",
            "{\"dependencies\":{\"net8.0\":{\"Fixture\":null}}}",
            "{\"dependencies\":{\"net8.0\":{\"Fixture\":{\"type\":\"Direct\"}}}}",
            "{\"dependencies\":{\"net8.0\":{\"Fixture\":{\"resolved\":123}}}}",
            "{\"dependencies\":{\"net8.0\":{\"Fixture\":{\"resolved\":\" \"}}}}",
            "{\"dependencies\":{\"net8.0\":{\"Valid\":{\"resolved\":\"1.0.0\"},\"Broken\":{}}}}"})
    void brokenLockCannotReturnASuccessfulInventory(String json, @TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("packages.lock.json"), json);
        Files.writeString(directory.resolve("fallback.csproj"),
                "<Project><ItemGroup><PackageReference Include=\"Fallback\" Version=\"1.0.0\" /></ItemGroup></Project>");
        var service = new DependencyManifestParserService(mock(MavenBomVersionResolver.class), mock(CondaPypiMappingService.class));
        assertThatThrownBy(() -> service.parseDependencies(directory, "fixture"))
                .isInstanceOf(com.salkcoding.oswl.exception.InvalidRequestException.class)
                .hasMessageContaining("packages.lock.json");
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"App\":{\"type\":\"Project\"}}"})
    void emptyAndProjectOnlyFrameworksAreValid(String packages, @TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("packages.lock.json"),
                "{\"version\":1,\"dependencies\":{\"net8.0\":" + packages + "}}");
        assertThat(new NugetManifestParser().parseNuGetLockFile(directory, "fixture")).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void everyFrameworkVersionReachesAdvisoryEvaluation(boolean vulnerableFirst, @TempDir Path directory) throws Exception {
        var mapper = new ObjectMapper();
        String first = vulnerableFirst ? "8.0.3" : "8.0.4";
        String second = vulnerableFirst ? "8.0.4" : "8.0.3";
        Map<String, Object> frameworks = new LinkedHashMap<>();
        frameworks.put("net8.0", Map.of("System.Text.Json", Map.of("type", "Direct", "requested", "[8.0.0, )", "resolved", first)));
        frameworks.put("net9.0", Map.of("System.Text.Json", Map.of("type", "Transitive", "resolved", second)));
        frameworks.put("net10.0", Map.of("System.Text.Json", Map.of("type", "Transitive", "resolved", first)));
        Files.writeString(directory.resolve("packages.lock.json"), mapper.writeValueAsString(
                Map.of("version", 1, "dependencies", frameworks)));

        List<ScanPayload.ComponentPayload> components = new NugetManifestParser().parseNuGetLockFile(directory, "fixture");
        assertThat(components).hasSize(2);
        assertThat(components).extracting(ScanPayload.ComponentPayload::getVersion)
                .containsExactly(first, second);
        assertThat(components).allSatisfy(component -> {
            assertThat(component.getName()).isEqualTo("System.Text.Json");
            assertThat(component.getEcosystem()).isEqualTo("NUGET");
        });
        var service = new DependencyManifestParserService(mock(MavenBomVersionResolver.class), mock(CondaPypiMappingService.class));
        var merged = service.parseDependencies(directory, "fixture");
        assertThat(merged.components()).extracting(ScanPayload.ComponentPayload::getVersion)
                .containsExactly(first, second);

        try (var input = getClass().getResourceAsStream("/advisories/GHSA-hh2w-p6rv-4g7w.json")) {
            assertThat(input).isNotNull();
            var advisory = mapper.readTree(input);
            int affectedCount = 0;
            for (var component : components) {
                boolean affected = "8.0.3".equals(component.getVersion());
                assertThat(OsvRangeEvaluator.evaluateAdvisory(advisory, "NuGet", component.getName(), component.getVersion()))
                        .isEqualTo(affected ? OsvRangeEvaluator.Result.AFFECTED : OsvRangeEvaluator.Result.NOT_AFFECTED);
                assertThat(OsvFixVersionSelector.select(advisory, "NuGet", component.getName(), component.getVersion()).version())
                        .isEqualTo(affected ? "8.0.4" : null);
                if (affected) affectedCount++;
            }
            assertThat(affectedCount).isEqualTo(1);
        }
    }
}
