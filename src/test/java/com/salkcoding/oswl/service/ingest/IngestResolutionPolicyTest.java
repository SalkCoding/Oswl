package com.salkcoding.oswl.service.ingest;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockConstruction;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class IngestResolutionPolicyTest {
    @TempDir Path root;

    @ParameterizedTest
    @CsvSource({"false, false, false", "true, false, false", "false, true, false", "true, true, true"})
    void staticAndAirgappedImportsDoNotStartPackageManagers(boolean build, boolean external, boolean airgapped) throws Exception {
        Files.writeString(root.resolve("package.json"), "{\"dependencies\":{\"example\":\"1.2.3\"}}");
        Files.writeString(root.resolve("pom.xml"), "<project><dependencies><dependency><groupId>example</groupId><artifactId>lib</artifactId><version>1.0</version></dependency></dependencies></project>");
        Files.writeString(root.resolve("build.gradle"), "dependencies { implementation 'example:other:2.0' }");
        Files.writeString(root.resolve("app.csproj"), "<Project><ItemGroup><PackageReference Include=\"Example\" Version=\"3.0.0\" /></ItemGroup></Project>");
        for (String wrapper : new String[]{"mvnw", "mvnw.cmd", "gradlew", "gradlew.bat"}) {
            Files.writeString(root.resolve(wrapper), "must never execute");
        }
        var parser = new DependencyManifestParserService(new MavenBomVersionResolver(), new CondaPypiMappingService());
        ReflectionTestUtils.setField(parser, "allowBuildExec", build);
        ReflectionTestUtils.setField(parser, "allowExternalResolution", external);
        ReflectionTestUtils.setField(parser, "airgapped", airgapped);
        try (var processes = mockConstruction(ProcessBuilder.class)) {
            var result = parser.parseDependencies(root, "local-fixture");
            assertThat(result.components()).extracting(component -> component.getName())
                    .contains("example", "example:lib", "Example");
            assertThat(processes.constructed()).isEmpty();
        }
        assertThat(root.resolve("package-lock.json")).doesNotExist();
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, true"})
    void bomFetchDoesNotIssueHttpWhenResolutionIsBlocked(boolean external, boolean airgapped) {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var resolver = new MavenBomVersionResolver();
        ReflectionTestUtils.setField(resolver, "restClient", builder.build());
        ReflectionTestUtils.setField(resolver, "allowExternalResolution", external);
        ReflectionTestUtils.setField(resolver, "airgapped", airgapped);
        Optional<byte[]> result = ReflectionTestUtils.invokeMethod(resolver, "fetchPomFromMavenCentral", "example", "bom", "1.0");
        assertThat(result).isEmpty();
        server.verify();
    }

    @Test
    void explicitOnlineOptInStillFetchesBom() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        server.expect(requestTo("https://repo.maven.apache.org/maven2/example/bom/1.0/bom-1.0.pom"))
                .andRespond(withSuccess("<project/>", MediaType.APPLICATION_XML));
        var resolver = new MavenBomVersionResolver();
        ReflectionTestUtils.setField(resolver, "restClient", builder.build());
        ReflectionTestUtils.setField(resolver, "allowExternalResolution", true);
        Optional<byte[]> result = ReflectionTestUtils.invokeMethod(resolver, "fetchPomFromMavenCentral", "example", "bom", "1.0");
        assertThat(result).isPresent();
        server.verify();
    }
}
