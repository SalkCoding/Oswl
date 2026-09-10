package com.salkcoding.oswl.service.ingest.parser;

import com.salkcoding.oswl.client.OsvClient;
import com.salkcoding.oswl.service.snapshot.AirgappedSnapshotService;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class NuGetDeclarationTest {
    @ParameterizedTest
    @ValueSource(strings = {"8.0.3", "8.0.4"})
    void sharedPackageKeepsEveryUnevaluatedCondition(String secondVersion, @TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("A.csproj"), """
                <Project><ItemGroup Condition="'$(TargetFramework)' == 'net8.0' And '$(RuntimeIdentifier)' == 'win-x64'">
                  <PackageReference Include="System.Text.Json" Version="8.0.3" Condition="'$(UseFirst)' == 'true'" />
                </ItemGroup></Project>
                """);
        Files.writeString(directory.resolve("B.csproj"), """
                <Project><ItemGroup Condition="'$(TargetFramework)' == 'net9.0' And '$(RuntimeIdentifier)' == 'linux-x64'">
                  <PackageReference Include="System.Text.Json" Version="%s" Condition="'$(UseSecond)' == 'true'" />
                </ItemGroup></Project>
                """.formatted(secondVersion));
        var service = new com.salkcoding.oswl.service.ingest.DependencyManifestParserService(
                mock(com.salkcoding.oswl.service.ingest.MavenBomVersionResolver.class),
                mock(com.salkcoding.oswl.service.ingest.CondaPypiMappingService.class));
        var components = service.parseDependencies(directory, "fixture").components();
        assertThat(components).hasSize(1);
        assertThat(components.getFirst().getVersion()).isNull();
        assertThat(components.getFirst().getDependencyInfo()).contains("8.0.3", secondVersion,
                "net8.0", "net9.0", "$(UseFirst)", "$(UseSecond)");
        assertThat(components.getFirst().getDependencyInfo().length()).isGreaterThan(300);
    }

    @ParameterizedTest
    @ValueSource(strings = {"8.0.3", "8.*", "[8.0.3,9.0)", "[8.0.3]", "$(UnresolvedVersion)"})
    void declarationDoesNotBecomeAnInstalledVersion(String requested, @TempDir Path directory) throws Exception {
        Files.writeString(directory.resolve("App.csproj"),
                "<Project><ItemGroup><PackageReference Include=\"System.Text.Json\" Version=\"" + requested
                        + "\" /></ItemGroup></Project>");
        var components = new NugetManifestParser().parseNuGetStatic(directory, "fixture").components();
        assertThat(components).hasSize(1);
        var component = components.getFirst();
        assertThat(component.getVersion()).isNull();
        assertThat(component.getDependencyInfo()).contains(requested);
        var queries = List.of(new OsvClient.OsvQuery("NuGet", component.getName(), component.getVersion()));
        var builder = org.springframework.web.client.RestClient.builder();
        var server = org.springframework.test.web.client.MockRestServiceServer.bindTo(builder).build();
        var online = new OsvClient();
        org.springframework.test.util.ReflectionTestUtils.setField(online, "restClient", builder.build());
        assertThat(online.queryBatch(queries).getFirst().resolved()).isFalse();
        assertThat(new OsvClient(mock(AirgappedSnapshotService.class), true).queryBatch(queries).getFirst().resolved()).isFalse();
        server.verify();
    }
}
