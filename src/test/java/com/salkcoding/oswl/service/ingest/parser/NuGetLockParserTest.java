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
