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
