package com.salkcoding.oswl.service;

import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.repository.ScanResultRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Tests for QuickImportService private dependency-parsing methods.
 * All private methods are accessed via reflection so production code stays unchanged.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("QuickImportService — private parser unit tests")
class QuickImportServiceParserTest {

    @Mock UserVcsConnectionRepository vcsConnectionRepository;
    @Mock EncryptionService            encryptionService;
    @Mock ProjectService               projectService;
    @Mock ApiKeyService                apiKeyService;
    @Mock ScanIngestService            scanIngestService;
    @Mock ScanResultRepository         scanResultRepository;
    @Mock GitHubService                gitHubService;
    @Mock EnrichmentProgressHolder     enrichmentProgressHolder;
    @Mock MavenBomVersionResolver      bomVersionResolver;

    @InjectMocks QuickImportService service;

    // ── reflection helpers ────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<ScanPayload.ComponentPayload> invokeParser(String methodName, Path dir) throws Exception {
        Method m = QuickImportService.class.getDeclaredMethod(methodName, Path.class, String.class);
        m.setAccessible(true);
        Object result = m.invoke(service, dir, "test-repo");
        // handle methods returning ParsedDependencies (private record)
        if (result instanceof List) {
            return (List<ScanPayload.ComponentPayload>) result;
        }
        Field components = result.getClass().getDeclaredField("components");
        components.setAccessible(true);
        return (List<ScanPayload.ComponentPayload>) components.get(result);
    }

    // ── parseMaven ────────────────────────────────────────────────────────

    @Test
    @DisplayName("parseMaven: pom.xml의 <dependencies>를 파싱하여 컴포넌트를 반환한다")
    void parseMaven_basicPom_returnsComponents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
            <project>
              <groupId>com.example</groupId>
              <artifactId>app</artifactId>
              <version>1.0</version>
              <dependencies>
                <dependency>
                  <groupId>org.springframework</groupId>
                  <artifactId>spring-core</artifactId>
                  <version>6.1.0</version>
                </dependency>
                <dependency>
                  <groupId>junit</groupId>
                  <artifactId>junit</artifactId>
                  <version>4.13</version>
                  <scope>test</scope>
                </dependency>
              </dependencies>
            </project>
            """);

        List<ScanPayload.ComponentPayload> comps = invokeParser("parseMaven", dir);

        assertThat(comps).hasSize(1); // test scope skipped
        assertThat(comps.get(0).getName()).isEqualTo("org.springframework:spring-core");
        assertThat(comps.get(0).getVersion()).isEqualTo("6.1.0");
        assertThat(comps.get(0).getEcosystem()).isEqualTo("MAVEN");
    }

    @Test
    @DisplayName("parseMaven: <dependencies>가 없으면 빈 목록을 반환한다")
    void parseMaven_noDependencies_returnsEmpty(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project><groupId>com.e</groupId></project>");

        List<ScanPayload.ComponentPayload> comps = invokeParser("parseMaven", dir);

        assertThat(comps).isEmpty();
    }

    @Test
    @DisplayName("parseMaven: 속성 참조 ${project.version}이 올바르게 해석된다")
    void parseMaven_propertyResolution_resolvesDollarRef(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), """
            <project>
              <version>2.0.0</version>
              <dependencies>
                <dependency>
                  <groupId>com.example</groupId>
                  <artifactId>lib</artifactId>
                  <version>${project.version}</version>
                </dependency>
              </dependencies>
            </project>
            """);

        List<ScanPayload.ComponentPayload> comps = invokeParser("parseMaven", dir);

        assertThat(comps).hasSize(1);
        assertThat(comps.get(0).getVersion()).isEqualTo("2.0.0");
    }

    // ── parseNpm (via package.json) ───────────────────────────────────────

    @Test
    @DisplayName("parseNpmPackageJson: package.json의 dependencies를 파싱한다")
    void parseNpmPackageJson_basicFile_returnsComponents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("package.json"), """
            {
              "name": "my-app",
              "dependencies": {
                "express": "^4.18.2",
                "lodash": "4.17.21"
              },
              "devDependencies": {
                "jest": "~29.0.0"
              }
            }
            """);

        List<ScanPayload.ComponentPayload> comps = invokeParser("parseNpmPackageJson", dir);

        assertThat(comps).hasSize(3);
        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .containsExactlyInAnyOrder("express", "lodash", "jest");
        // Version range prefixes should be stripped
        assertThat(comps.stream().filter(c -> "express".equals(c.getName())).findFirst())
                .hasValueSatisfying(c -> assertThat(c.getVersion()).isEqualTo("4.18.2"));
    }

    @Test
    @DisplayName("parseNpmLock: package-lock.json v2 (packages) 형식을 파싱한다")
    void parseNpmLock_v2Format_returnsComponents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("package-lock.json"), """
            {
              "lockfileVersion": 2,
              "packages": {
                "": { "name": "root" },
                "node_modules/express": { "version": "4.18.2" },
                "node_modules/lodash": { "version": "4.17.21" }
              }
            }
            """);

        List<ScanPayload.ComponentPayload> comps = invokeParser("parseNpmLock", dir);

        assertThat(comps).hasSize(2);
        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .containsExactlyInAnyOrder("express", "lodash");
    }

    @Test
    @DisplayName("parseNpmLock: package-lock.json v1 (dependencies) 형식을 파싱한다")
    void parseNpmLock_v1Format_returnsComponents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("package-lock.json"), """
            {
              "lockfileVersion": 1,
              "dependencies": {
                "express": { "version": "4.18.2" },
                "lodash": { "version": "4.17.21" }
              }
            }
            """);

        List<ScanPayload.ComponentPayload> comps = invokeParser("parseNpmLock", dir);

        assertThat(comps).hasSize(2);
    }

    // ── parsePython ───────────────────────────────────────────────────────

    @Test
    @DisplayName("parsePython: requirements.txt의 패키지를 파싱한다")
    void parsePython_requirements_returnsComponents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("requirements.txt"), """
            # comment line
            requests==2.31.0
            flask>=2.0.0
            gunicorn
            -r other.txt
            """);

        List<ScanPayload.ComponentPayload> comps = invokeParser("parsePython", dir);

        assertThat(comps).hasSize(3);
        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .containsExactlyInAnyOrder("requests", "flask", "gunicorn");
        assertThat(comps.stream().filter(c -> "requests".equals(c.getName())).findFirst())
                .hasValueSatisfying(c -> assertThat(c.getVersion()).isEqualTo("2.31.0"));
    }

    @Test
    @DisplayName("parsePython: requirements.txt가 없으면 빈 목록을 반환한다")
    void parsePython_noFile_returnsEmpty(@TempDir Path dir) throws Exception {
        List<ScanPayload.ComponentPayload> comps = invokeParser("parsePython", dir);

        assertThat(comps).isEmpty();
    }

    // ── parseGoMod ────────────────────────────────────────────────────────

    @Test
    @DisplayName("parseGoMod: go.mod의 require 블록을 파싱한다")
    void parseGoMod_requireBlock_returnsComponents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("go.mod"), """
            module example.com/myapp

            go 1.21

            require (
                github.com/gin-gonic/gin v1.9.1
                golang.org/x/crypto v0.18.0 // indirect
            )
            """);

        List<ScanPayload.ComponentPayload> comps = invokeParser("parseGoMod", dir);

        assertThat(comps).hasSize(2);
        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .containsExactlyInAnyOrder("github.com/gin-gonic/gin", "golang.org/x/crypto");
    }

    @Test
    @DisplayName("parseGoMod: single-line require 구문도 파싱한다")
    void parseGoMod_singleLineRequire_returnsComponent(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("go.mod"), """
            module example.com/app
            go 1.20
            require github.com/pkg/errors v0.9.1
            """);

        List<ScanPayload.ComponentPayload> comps = invokeParser("parseGoMod", dir);

        assertThat(comps).hasSize(1);
        assertThat(comps.get(0).getName()).isEqualTo("github.com/pkg/errors");
        assertThat(comps.get(0).getVersion()).isEqualTo("v0.9.1");
        assertThat(comps.get(0).getEcosystem()).isEqualTo("GO");
    }

    // ── parseCargo ────────────────────────────────────────────────────────

    @Test
    @DisplayName("parseCargo: Cargo.lock ([[package]] blocks)를 파싱한다")
    void parseCargo_cargoLock_returnsComponents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Cargo.lock"), """
            # This file is automatically generated by Cargo.
            [[package]]
            name = "serde"
            version = "1.0.196"

            [[package]]
            name = "tokio"
            version = "1.35.1"
            """);

        List<ScanPayload.ComponentPayload> comps = invokeParser("parseCargo", dir);

        assertThat(comps).hasSize(2);
        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .containsExactlyInAnyOrder("serde", "tokio");
        assertThat(comps.get(0).getEcosystem()).isEqualTo("CARGO");
    }

    // ── parseNuGetStatic ─────────────────────────────────────────────────

    @Test
    @DisplayName("parseNuGetStatic: .csproj의 PackageReference를 파싱한다")
    void parseNuGetStatic_csproj_returnsComponents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("MyApp.csproj"), """
            <Project Sdk="Microsoft.NET.Sdk">
              <ItemGroup>
                <PackageReference Include="Newtonsoft.Json" Version="13.0.3" />
                <PackageReference Include="Microsoft.Extensions.Logging" Version="8.0.0" />
              </ItemGroup>
            </Project>
            """);

        List<ScanPayload.ComponentPayload> comps = invokeParser("parseNuGetStatic", dir);

        assertThat(comps).hasSize(2);
        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .containsExactlyInAnyOrder("Newtonsoft.Json", "Microsoft.Extensions.Logging");
        assertThat(comps.get(0).getEcosystem()).isEqualTo("NUGET");
    }

    // ── parseGradleStatic ─────────────────────────────────────────────────

    @Test
    @DisplayName("parseGradleStatic: build.gradle의 implementation 선언을 파싱한다")
    void parseGradleStatic_buildGradle_returnsComponents(@TempDir Path dir) throws Exception {
        when(bomVersionResolver.parseGradleDeclaredWithBom(any())).thenReturn(List.of(
                ScanPayload.ComponentPayload.create(
                        "org.springframework.boot:spring-boot-starter", "3.2.0", "MAVEN", "Gradle (declared + BOM)", List.of()),
                ScanPayload.ComponentPayload.create(
                        "org.junit.jupiter:junit-jupiter", "5.10.1", "MAVEN", "Gradle (declared + BOM)", List.of()),
                ScanPayload.ComponentPayload.create(
                        "com.h2database:h2", "2.2.224", "MAVEN", "Gradle (declared + BOM)", List.of())));
        Files.writeString(dir.resolve("build.gradle"), """
            plugins { id 'java' }

            dependencies {
                implementation 'org.springframework.boot:spring-boot-starter:3.2.0'
                testImplementation 'org.junit.jupiter:junit-jupiter:5.10.1'
                runtimeOnly 'com.h2database:h2:2.2.224'
            }
            """);

        List<ScanPayload.ComponentPayload> comps = invokeParser("parseGradleStatic", dir);

        assertThat(comps).hasSize(3);
        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .containsExactlyInAnyOrder(
                        "org.springframework.boot:spring-boot-starter",
                        "org.junit.jupiter:junit-jupiter",
                        "com.h2database:h2");
        assertThat(comps.get(0).getEcosystem()).isEqualTo("MAVEN");
    }

    @Test
    @DisplayName("parseGradleStatic: 빌드 파일이 없으면 빈 목록을 반환한다")
    void parseGradleStatic_noFile_returnsEmpty(@TempDir Path dir) throws Exception {
        List<ScanPayload.ComponentPayload> comps = invokeParser("parseGradleStatic", dir);

        assertThat(comps).isEmpty();
    }

    // ── resolveProp (via reflection) ──────────────────────────────────────

    @Test
    @DisplayName("resolveProp: ${prop}을 props 맵에서 치환한다")
    void resolveProp_knownProp_replacesValue() throws Exception {
        Method m = QuickImportService.class.getDeclaredMethod("resolveProp", String.class, java.util.Map.class);
        m.setAccessible(true);

        String result = (String) m.invoke(service, "${project.version}", java.util.Map.of("project.version", "3.0.0"));

        assertThat(result).isEqualTo("3.0.0");
    }

    @Test
    @DisplayName("resolveProp: 알 수 없는 속성은 원래 ${...} 표현식을 그대로 반환한다")
    void resolveProp_unknownProp_returnsOriginalExpression() throws Exception {
        Method m = QuickImportService.class.getDeclaredMethod("resolveProp", String.class, java.util.Map.class);
        m.setAccessible(true);

        Object result = m.invoke(service, "${unknown.prop}", java.util.Map.of());

        assertThat(result).isEqualTo("${unknown.prop}");
    }

    @Test
    @DisplayName("resolveProp: 일반 문자열은 그대로 반환한다")
    void resolveProp_literal_returnsAsIs() throws Exception {
        Method m = QuickImportService.class.getDeclaredMethod("resolveProp", String.class, java.util.Map.class);
        m.setAccessible(true);

        String result = (String) m.invoke(service, "1.2.3", java.util.Map.of());

        assertThat(result).isEqualTo("1.2.3");
    }

    // ── parseRepoUrl (via reflection) ─────────────────────────────────────

    @Test
    @DisplayName("parseRepoUrl: GitHub URL에서 owner/repo를 추출한다")
    void parseRepoUrl_github_extractsOwnerAndRepo() throws Exception {
        Method m = QuickImportService.class.getDeclaredMethod("parseRepoUrl", String.class, List.class);
        m.setAccessible(true);

        Object result = m.invoke(service, "https://github.com/SalkCoding/Oswl", List.of());

        assertThat(result).isNotNull();
        Field owner = result.getClass().getDeclaredField("owner");
        Field repo  = result.getClass().getDeclaredField("repo");
        owner.setAccessible(true);
        repo.setAccessible(true);
        assertThat(owner.get(result)).isEqualTo("SalkCoding");
        assertThat(repo.get(result)).isEqualTo("Oswl");
    }

    @Test
    @DisplayName("parseRepoUrl: GitLab URL에서 provider를 GITLAB으로 감지한다")
    void parseRepoUrl_gitlab_detectsGitLabProvider() throws Exception {
        Method m = QuickImportService.class.getDeclaredMethod("parseRepoUrl", String.class, List.class);
        m.setAccessible(true);

        Object result = m.invoke(service, "https://gitlab.com/owner/repo.git", List.of());

        assertThat(result).isNotNull();
        Field provider = result.getClass().getDeclaredField("provider");
        provider.setAccessible(true);
        assertThat(provider.get(result).toString()).isEqualTo("GITLAB");
    }

    @Test
    @DisplayName("parseRepoUrl: Bitbucket URL에서 provider를 BITBUCKET으로 감지한다")
    void parseRepoUrl_bitbucket_detectsBitbucketProvider() throws Exception {
        Method m = QuickImportService.class.getDeclaredMethod("parseRepoUrl", String.class, List.class);
        m.setAccessible(true);

        Object result = m.invoke(service, "https://bitbucket.org/workspace/myrepo", List.of());

        assertThat(result).isNotNull();
        Field provider = result.getClass().getDeclaredField("provider");
        provider.setAccessible(true);
        assertThat(provider.get(result).toString()).isEqualTo("BITBUCKET");
    }

    @Test
    @DisplayName("parseRepoUrl: null URL은 null을 반환한다")
    void parseRepoUrl_nullUrl_returnsNull() throws Exception {
        Method m = QuickImportService.class.getDeclaredMethod("parseRepoUrl", String.class, List.class);
        m.setAccessible(true);

        Object result = m.invoke(service, (Object) null, List.of());

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("parseRepoUrl: 알 수 없는 호스트는 null을 반환한다")
    void parseRepoUrl_unknownHost_returnsNull() throws Exception {
        Method m = QuickImportService.class.getDeclaredMethod("parseRepoUrl", String.class, List.class);
        m.setAccessible(true);

        Object result = m.invoke(service, "https://unknownhost.example.com/user/repo", List.of());

        assertThat(result).isNull();
    }
}
