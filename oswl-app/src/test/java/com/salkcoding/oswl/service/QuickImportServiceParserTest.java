package com.salkcoding.oswl.service;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.auth.repository.UserVcsConnectionRepository;
import com.salkcoding.oswl.auth.security.EncryptionService;
import com.salkcoding.oswl.auth.service.AuditLogService;
import com.salkcoding.oswl.client.BitbucketCloudClient;
import com.salkcoding.oswl.service.git.GitCloneExecutor;
import com.salkcoding.oswl.service.manifest.NpmPackageJsonParser;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Tests for {@link DependencyManifestParserService} (via reflection) and
 * {@link QuickImportService#parseRepoUrl} URL parsing.
 */
@Tag(TestTags.PARSER)
@ExtendWith(MockitoExtension.class)
@DisplayName("DependencyManifestParserService — parser unit tests")
class QuickImportServiceParserTest {

    @Mock MavenBomVersionResolver bomVersionResolver;

    @Mock UserVcsConnectionRepository vcsConnectionRepository;
    @Mock EncryptionService            encryptionService;
    @Mock ProjectService               projectService;
    @Mock ApiKeyService                apiKeyService;
    @Mock ScanIngestService            scanIngestService;
    @Mock ScanResultRepository         scanResultRepository;
    @Mock GitHubService                gitHubService;
    @Mock BitbucketCloudClient         bitbucketCloudClient;
    @Mock EnrichmentProgressHolder     enrichmentProgressHolder;
    @Mock GitCloneExecutor              gitCloneExecutor;
    @Mock com.salkcoding.oswl.auth.repository.UserRepository userRepository;
    @Mock AuditLogService               auditLogService;
    @Mock ProjectCliKeyPolicyService    projectCliKeyPolicyService;
    @Mock org.springframework.context.MessageSource messageSource;

    @InjectMocks DependencyManifestParserService parserService;
    @InjectMocks QuickImportService quickImportService;

    private static final String REPO = "test-repo";

    @org.junit.jupiter.api.BeforeEach
    void stubBomResolver() {
        lenient().when(bomVersionResolver.enrichComponentVersions(any(), any()))
                .thenAnswer(inv -> inv.getArgument(1));
    }

    @SuppressWarnings("unchecked")
    private List<ScanPayload.ComponentPayload> invokeList(String methodName, Path dir) throws Exception {
        Method m = DependencyManifestParserService.class.getDeclaredMethod(methodName, Path.class, String.class);
        m.setAccessible(true);
        Object result = m.invoke(parserService, dir, REPO);
        if (result == null) {
            return List.of();
        }
        if (result instanceof List<?> list) {
            return (List<ScanPayload.ComponentPayload>) list;
        }
        return extractComponents(result);
    }

    @SuppressWarnings("unchecked")
    private List<ScanPayload.ComponentPayload> invokeMavenPom(Path pomFile, Path projectDir) throws Exception {
        Method m = DependencyManifestParserService.class.getDeclaredMethod(
                "parseSingleMavenPom", Path.class, Path.class, String.class);
        m.setAccessible(true);
        Object result = m.invoke(parserService, pomFile, projectDir, REPO);
        return result != null ? (List<ScanPayload.ComponentPayload>) result : List.of();
    }

    @SuppressWarnings("unchecked")
    private List<ScanPayload.ComponentPayload> invokeTomlLock(Path lockFile, String ecosystem) throws Exception {
        Method m = DependencyManifestParserService.class.getDeclaredMethod(
                "parseTomlPackageLock", Path.class, String.class, String.class);
        m.setAccessible(true);
        Object result = m.invoke(parserService, lockFile, ecosystem, REPO);
        return result != null ? (List<ScanPayload.ComponentPayload>) result : List.of();
    }

    @SuppressWarnings("unchecked")
    private List<ScanPayload.ComponentPayload> extractComponents(Object parsedDeps) throws Exception {
        Field components = parsedDeps.getClass().getDeclaredField("components");
        components.setAccessible(true);
        return (List<ScanPayload.ComponentPayload>) components.get(parsedDeps);
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

        List<ScanPayload.ComponentPayload> comps = invokeMavenPom(dir.resolve("pom.xml"), dir);

        assertThat(comps).hasSize(1); // test scope skipped
        assertThat(comps.get(0).getName()).isEqualTo("org.springframework:spring-core");
        assertThat(comps.get(0).getVersion()).isEqualTo("6.1.0");
        assertThat(comps.get(0).getEcosystem()).isEqualTo("MAVEN");
    }

    @Test
    @DisplayName("parseMaven: <dependencies>가 없으면 빈 목록을 반환한다")
    void parseMaven_noDependencies_returnsEmpty(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pom.xml"), "<project><groupId>com.e</groupId></project>");

        List<ScanPayload.ComponentPayload> comps = invokeMavenPom(dir.resolve("pom.xml"), dir);

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

        List<ScanPayload.ComponentPayload> comps = invokeMavenPom(dir.resolve("pom.xml"), dir);

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

        List<ScanPayload.ComponentPayload> comps = NpmPackageJsonParser.parse(dir, REPO);

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

        List<ScanPayload.ComponentPayload> comps = invokeList("parseNpmLock", dir);

        assertThat(comps).hasSize(2);
        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .containsExactlyInAnyOrder("express", "lodash");
    }

    @Test
    @DisplayName("parseNpmLock: package-lock.json v3 (lockfileVersion 3) 형식을 파싱한다")
    void parseNpmLock_v3Format_returnsComponents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("package-lock.json"), """
            {
              "lockfileVersion": 3,
              "packages": {
                "": { "name": "koa" },
                "node_modules/koa": { "version": "2.15.0" },
                "node_modules/accepts": { "version": "1.3.8" }
              }
            }
            """);

        List<ScanPayload.ComponentPayload> comps = invokeList("parseNpmLock", dir);

        assertThat(comps).hasSize(2);
        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .containsExactlyInAnyOrder("koa", "accepts");
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

        List<ScanPayload.ComponentPayload> comps = invokeList("parseNpmLock", dir);

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

        List<ScanPayload.ComponentPayload> comps = invokeList("parsePython", dir);

        assertThat(comps).hasSize(3);
        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .containsExactlyInAnyOrder("requests", "flask", "gunicorn");
        assertThat(comps.stream().filter(c -> "requests".equals(c.getName())).findFirst())
                .hasValueSatisfying(c -> assertThat(c.getVersion()).isEqualTo("2.31.0"));
    }

    @Test
    @DisplayName("parsePyprojectToml: [project].dependencies를 파싱한다")
    void parsePyprojectToml_projectDeps_returnsComponents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("pyproject.toml"), """
            [build-system]
            requires = ["setuptools"]

            [project]
            name = "demo"
            version = "0.1.0"
            dependencies = [
              "requests>=2.31.0",
              "flask==3.0.0",
            ]
            """);

        List<ScanPayload.ComponentPayload> comps = invokeList("parsePyprojectToml",
                dir.resolve("pyproject.toml"));

        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .containsExactlyInAnyOrder("requests", "flask");
        assertThat(comps).filteredOn(c -> "flask".equals(c.getName()))
                .extracting(ScanPayload.ComponentPayload::getVersion)
                .containsExactly("3.0.0");
    }

    @Test
    @DisplayName("parseRequirementsFile: 여러 requirements.txt를 각각 파싱한다")
    void parseRequirementsFile_multipleFiles_mergeDistinct(@TempDir Path dir) throws Exception {
        Files.createDirectories(dir.resolve("api"));
        Files.writeString(dir.resolve("requirements.txt"), "requests==2.31.0\n");
        Files.writeString(dir.resolve("api/requirements.txt"), "flask>=3.0.0\n");

        List<ScanPayload.ComponentPayload> root = invokeList("parseRequirementsFile",
                dir.resolve("requirements.txt"));
        List<ScanPayload.ComponentPayload> api = invokeList("parseRequirementsFile",
                dir.resolve("api/requirements.txt"));

        assertThat(root).extracting(ScanPayload.ComponentPayload::getName).containsExactly("requests");
        assertThat(api).extracting(ScanPayload.ComponentPayload::getName).containsExactly("flask");
    }

    @Test
    @DisplayName("parsePython: requirements.txt가 없으면 빈 목록을 반환한다")
    void parsePython_noFile_returnsEmpty(@TempDir Path dir) throws Exception {
        List<ScanPayload.ComponentPayload> comps = invokeList("parsePython", dir);

        assertThat(comps).isEmpty();
    }

    @Test
    @DisplayName("parseTomlPackageLock: poetry.lock [[package]] 블록을 파싱한다")
    void parsePoetryLock_returnsComponents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("poetry.lock"), """
            [[package]]
            name = "httpx"
            version = "0.27.0"

            [[package]]
            name = "anyio"
            version = "4.3.0"
            """);

        List<ScanPayload.ComponentPayload> comps = invokeTomlLock(dir.resolve("poetry.lock"), "PYPI");

        assertThat(comps).hasSize(2);
        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .containsExactlyInAnyOrder("httpx", "anyio");
    }

    @Test
    @DisplayName("parsePipfileLock: Pipfile.lock default/develop 섹션을 파싱한다")
    void parsePipfileLock_returnsComponents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Pipfile.lock"), """
            {
              "default": {
                "requests": { "version": "==2.31.0" }
              },
              "develop": {
                "pytest": { "version": "==8.0.0" }
              }
            }
            """);

        List<ScanPayload.ComponentPayload> comps = invokeList("parsePipfileLock", dir);

        assertThat(comps).hasSize(2);
        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .containsExactlyInAnyOrder("requests", "pytest");
    }

    @Test
    @DisplayName("parseGoSum: go.sum 모듈/버전 쌍을 파싱한다")
    void parseGoSum_returnsComponents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("go.sum"), """
            github.com/gin-gonic/gin v1.9.1 h1=abc
            github.com/gin-gonic/gin v1.9.1/go.mod h1=def
            golang.org/x/net v0.20.0 h1=ghi
            """);

        List<ScanPayload.ComponentPayload> comps = invokeList("parseGoSum", dir);

        assertThat(comps).hasSize(2);
        assertThat(comps.get(0).getEcosystem()).isEqualTo("GO");
    }

    @Test
    @DisplayName("parseGemfileLock: Gemfile.lock specs 섹션을 파싱한다")
    void parseGemfileLock_returnsComponents(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Gemfile.lock"), """
            GEM
              remote: https://rubygems.org/
              specs:
                jekyll (4.3.3)
                  mercenary (~> 0.3)
                mercenary (0.4.0)
            """);

        List<ScanPayload.ComponentPayload> comps = invokeList("parseGemfileLock", dir);

        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .contains("jekyll", "mercenary");
        assertThat(comps.get(0).getEcosystem()).isEqualTo("RUBYGEMS");
    }

    @Test
    @DisplayName("parseGemfileLock: pre-release 버전(8.2.0.alpha)을 파싱한다")
    void parseGemfileLock_prereleaseVersion_parsed(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("Gemfile.lock"), """
            PATH
              remote: .
              specs:
                actioncable (8.2.0.alpha)
                action_text-trix (2.1.16)
            """);

        List<ScanPayload.ComponentPayload> comps = invokeList("parseGemfileLock", dir);

        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .contains("actioncable", "action_text-trix");
        assertThat(comps).filteredOn(c -> "actioncable".equals(c.getName()))
                .extracting(ScanPayload.ComponentPayload::getVersion)
                .containsExactly("8.2.0.alpha");
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

        List<ScanPayload.ComponentPayload> comps = invokeList("parseGoModDeclared", dir);

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

        List<ScanPayload.ComponentPayload> comps = invokeList("parseGoModDeclared", dir);

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

        List<ScanPayload.ComponentPayload> comps = invokeTomlLock(dir.resolve("Cargo.lock"), "CARGO");

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

        List<ScanPayload.ComponentPayload> comps = invokeList("parseNuGetStatic", dir);

        assertThat(comps).hasSize(2);
        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .containsExactlyInAnyOrder("Newtonsoft.Json", "Microsoft.Extensions.Logging");
        assertThat(comps.get(0).getEcosystem()).isEqualTo("NUGET");
    }

    @Test
    @DisplayName("parseNuGetStatic: 중첩 .csproj와 자식 Version 요소를 파싱한다")
    void parseNuGetStatic_nestedCsprojChildVersion_parsesAll(@TempDir Path dir) throws Exception {
        Path nested = dir.resolve("src/App");
        Files.createDirectories(nested);
        Files.createDirectories(dir.resolve("eng"));
        Files.writeString(nested.resolve("App.csproj"), """
            <Project Sdk="Microsoft.NET.Sdk">
              <ItemGroup>
                <PackageReference Include="Contoso.Api">
                  <Version>2.1.0</Version>
                </PackageReference>
              </ItemGroup>
            </Project>
            """);
        Files.writeString(dir.resolve("eng/Versions.props"), """
            <Project>
              <PropertyGroup>
                <SharedLibVersion>1.0.5</SharedLibVersion>
              </PropertyGroup>
            </Project>
            """);
        Files.writeString(dir.resolve("Lib.csproj"), """
            <Project Sdk="Microsoft.NET.Sdk">
              <ItemGroup>
                <PackageReference Include="Shared.Lib" Version="$(SharedLibVersion)" />
              </ItemGroup>
            </Project>
            """);

        List<ScanPayload.ComponentPayload> comps = invokeList("parseNuGetStatic", dir);

        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .contains("Contoso.Api", "Shared.Lib");
        assertThat(comps).filteredOn(c -> "Shared.Lib".equals(c.getName()))
                .extracting(ScanPayload.ComponentPayload::getVersion)
                .containsExactly("1.0.5");
    }

    @Test
    @DisplayName("parseDotNetListJson: dotnet list JSON을 파싱한다")
    void parseDotNetListJson_basic_returnsComponents(@TempDir Path dir) throws Exception {
        String json = """
            {
              "projects": [
                {
                  "frameworks": [
                    {
                      "topLevelPackages": [
                        { "id": "Newtonsoft.Json", "resolvedVersion": "13.0.3" }
                      ],
                      "transitivePackages": [
                        { "id": "System.Text.Json", "resolvedVersion": "8.0.0" }
                      ]
                    }
                  ]
                }
              ]
            }
            """;
        Method m = DependencyManifestParserService.class.getDeclaredMethod(
                "parseDotNetListJson", String.class, String.class);
        m.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<ScanPayload.ComponentPayload> comps =
                (List<ScanPayload.ComponentPayload>) m.invoke(parserService, json, REPO);

        assertThat(comps).hasSize(2);
        assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
                .containsExactlyInAnyOrder("Newtonsoft.Json", "System.Text.Json");
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

        List<ScanPayload.ComponentPayload> comps = invokeList("parseGradleStatic", dir);

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
        List<ScanPayload.ComponentPayload> comps = invokeList("parseGradleStatic", dir);

        assertThat(comps).isEmpty();
    }

    // ── resolveProp (via reflection) ──────────────────────────────────────

    @Test
    @DisplayName("resolveProp: ${prop}을 props 맵에서 치환한다")
    void resolveProp_knownProp_replacesValue() throws Exception {
        Method m = DependencyManifestParserService.class.getDeclaredMethod("resolveProp", String.class, java.util.Map.class);
        m.setAccessible(true);

        String result = (String) m.invoke(parserService, "${project.version}", java.util.Map.of("project.version", "3.0.0"));

        assertThat(result).isEqualTo("3.0.0");
    }

    @Test
    @DisplayName("resolveProp: 알 수 없는 속성은 원래 ${...} 표현식을 그대로 반환한다")
    void resolveProp_unknownProp_returnsOriginalExpression() throws Exception {
        Method m = DependencyManifestParserService.class.getDeclaredMethod("resolveProp", String.class, java.util.Map.class);
        m.setAccessible(true);

        Object result = m.invoke(parserService, "${unknown.prop}", java.util.Map.of());

        assertThat(result).isEqualTo("${unknown.prop}");
    }

    @Test
    @DisplayName("resolveProp: 일반 문자열은 그대로 반환한다")
    void resolveProp_literal_returnsAsIs() throws Exception {
        Method m = DependencyManifestParserService.class.getDeclaredMethod("resolveProp", String.class, java.util.Map.class);
        m.setAccessible(true);

        String result = (String) m.invoke(parserService, "1.2.3", java.util.Map.of());

        assertThat(result).isEqualTo("1.2.3");
    }

    // ── parseRepoUrl (via reflection) ─────────────────────────────────────

    @Test
    @DisplayName("parseRepoUrl: GitHub URL에서 owner/repo를 추출한다")
    void parseRepoUrl_github_extractsOwnerAndRepo() throws Exception {
        Method m = QuickImportService.class.getDeclaredMethod("parseRepoUrl", String.class, List.class);
        m.setAccessible(true);

        Object result = m.invoke(quickImportService, "https://github.com/SalkCoding/Oswl", List.of());

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

        Object result = m.invoke(quickImportService, "https://gitlab.com/owner/repo.git", List.of());

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

        Object result = m.invoke(quickImportService, "https://bitbucket.org/workspace/myrepo", List.of());

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

        Object result = m.invoke(quickImportService, (Object) null, List.of());

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("parseRepoUrl: 알 수 없는 호스트는 null을 반환한다")
    void parseRepoUrl_unknownHost_returnsNull() throws Exception {
        Method m = QuickImportService.class.getDeclaredMethod("parseRepoUrl", String.class, List.class);
        m.setAccessible(true);

        Object result = m.invoke(quickImportService, "https://unknownhost.example.com/user/repo", List.of());

        assertThat(result).isNull();
    }
}
