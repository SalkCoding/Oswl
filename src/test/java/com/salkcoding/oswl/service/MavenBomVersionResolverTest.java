package com.salkcoding.oswl.service;

import com.salkcoding.oswl.dto.scan.ScanPayload;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MavenBomVersionResolver")
class MavenBomVersionResolverTest {

  private static final String SAMPLE_BOM_POM = """
      <?xml version="1.0" encoding="UTF-8"?>
      <project>
        <modelVersion>4.0.0</modelVersion>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-dependencies</artifactId>
        <version>3.5.5</version>
        <dependencyManagement>
          <dependencies>
            <dependency>
              <groupId>org.springframework.boot</groupId>
              <artifactId>spring-boot-starter-web</artifactId>
              <version>3.5.5</version>
            </dependency>
            <dependency>
              <groupId>org.jetbrains.kotlin</groupId>
              <artifactId>kotlin-reflect</artifactId>
              <version>1.9.25</version>
            </dependency>
          </dependencies>
        </dependencyManagement>
      </project>
      """;

  @Test
  @DisplayName("Spring Boot plugin 버전으로 BOM에서 버전 없는 의존성을 해석한다")
  void parseGradleDeclaredWithBom_springBootPlugin_resolvesVersions(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("build.gradle"), """
        plugins {
            id 'org.springframework.boot' version '3.5.5'
            id 'io.spring.dependency-management' version '1.1.7'
        }
        dependencies {
            implementation 'org.springframework.boot:spring-boot-starter-web'
            implementation 'org.jetbrains.kotlin:kotlin-reflect'
            implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.13'
        }
        """);

    MavenBomVersionResolver resolver = new MavenBomVersionResolver(
        (g, a, v) -> {
          if ("org.springframework.boot".equals(g)
              && "spring-boot-dependencies".equals(a)
              && "3.5.5".equals(v)) {
            return Optional.of(SAMPLE_BOM_POM.getBytes(StandardCharsets.UTF_8));
          }
          return Optional.empty();
        });

    List<ScanPayload.ComponentPayload> comps = resolver.parseGradleDeclaredWithBom(dir);

    assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
        .contains(
            "org.springframework.boot:spring-boot-starter-web",
            "org.jetbrains.kotlin:kotlin-reflect",
            "org.springdoc:springdoc-openapi-starter-webmvc-ui");
    assertThat(comps).filteredOn(c -> "org.springframework.boot:spring-boot-starter-web".equals(c.getName()))
        .extracting(ScanPayload.ComponentPayload::getVersion)
        .containsExactly("3.5.5");
    assertThat(comps).filteredOn(c -> "org.jetbrains.kotlin:kotlin-reflect".equals(c.getName()))
        .extracting(ScanPayload.ComponentPayload::getVersion)
        .containsExactly("1.9.25");
    assertThat(comps).filteredOn(c -> "org.springdoc:springdoc-openapi-starter-webmvc-ui".equals(c.getName()))
        .extracting(ScanPayload.ComponentPayload::getVersion)
        .containsExactly("2.8.13");
  }

  @Test
  @DisplayName("enrichComponentVersions: null version 컴포넌트에 BOM 버전을 채운다")
  void enrichComponentVersions_nullVersion_fillsFromIndex(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("build.gradle"), """
        plugins { id 'org.springframework.boot' version '3.5.5' }
        """);

    MavenBomVersionResolver resolver = new MavenBomVersionResolver(
        (g, a, v) -> Optional.of(SAMPLE_BOM_POM.getBytes(StandardCharsets.UTF_8)));

    List<ScanPayload.ComponentPayload> input = List.of(
        ScanPayload.ComponentPayload.create(
            "org.springframework.boot:spring-boot-starter-web", null, "MAVEN", "Direct", List.of()));

    List<ScanPayload.ComponentPayload> enriched = resolver.enrichComponentVersions(dir, input);

    assertThat(enriched).hasSize(1);
    assertThat(enriched.get(0).getVersion()).isEqualTo("3.5.5");
  }

  @Test
  @DisplayName("Kotlin DSL mavenBom($var) 및 buildSrc const val로 BOM 버전을 해석한다")
  void parseGradleDeclaredWithBom_kotlinDslVariables_resolvesVersions(@TempDir Path dir) throws Exception {
    Path buildSrc = dir.resolve("buildSrc/src/main/kotlin");
    Files.createDirectories(buildSrc);
    Files.writeString(buildSrc.resolve("Versions.kt"), """
        object Versions {
            const val KOTLIN_VERSION = "2.2.20"
        }
        """);
    Files.writeString(dir.resolve("build.gradle.kts"), """
        extra["sb.version"] = "4.0.0"
        val springBootVersion = extra["sb.version"] as String
        dependencyManagement {
            imports {
                mavenBom("org.jetbrains.kotlin:kotlin-bom:${Versions.KOTLIN_VERSION}")
                mavenBom("org.springframework.boot:spring-boot-dependencies:$springBootVersion")
            }
        }
        """);
  Files.createDirectories(dir.resolve("graphql-dgs"));
  Files.writeString(dir.resolve("graphql-dgs/build.gradle.kts"), """
        dependencies {
            implementation("org.springframework:spring-web")
            implementation("org.springframework.boot:spring-boot-autoconfigure")
        }
        """);

    String springBomPom = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project>
          <dependencyManagement>
            <dependencies>
              <dependency>
                <groupId>org.springframework</groupId>
                <artifactId>spring-web</artifactId>
                <version>6.2.0</version>
              </dependency>
              <dependency>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-autoconfigure</artifactId>
                <version>4.0.0</version>
              </dependency>
            </dependencies>
          </dependencyManagement>
        </project>
        """;

    MavenBomVersionResolver resolver = new MavenBomVersionResolver(
        (g, a, v) -> {
          if ("org.springframework.boot".equals(g)
              && "spring-boot-dependencies".equals(a)
              && "4.0.0".equals(v)) {
            return Optional.of(springBomPom.getBytes(StandardCharsets.UTF_8));
          }
          return Optional.empty();
        });

    List<ScanPayload.ComponentPayload> comps = resolver.parseGradleDeclaredWithBom(dir);

    assertThat(comps).extracting(ScanPayload.ComponentPayload::getName)
        .contains("org.springframework:spring-web", "org.springframework.boot:spring-boot-autoconfigure");
    assertThat(comps).filteredOn(c -> "org.springframework:spring-web".equals(c.getName()))
        .extracting(ScanPayload.ComponentPayload::getVersion)
        .containsExactly("6.2.0");
    assertThat(comps).filteredOn(c -> "org.springframework.boot:spring-boot-autoconfigure".equals(c.getName()))
        .extracting(ScanPayload.ComponentPayload::getVersion)
        .containsExactly("4.0.0");
  }

  @Test
  @DisplayName("buildVersionIndex: mavenBom import 좌표를 인덱스에 병합한다")
  void buildVersionIndex_mavenBomImport_includesManagedVersions(@TempDir Path dir) throws Exception {
    Files.writeString(dir.resolve("build.gradle"), """
        dependencyManagement {
            imports {
                mavenBom 'org.springframework.boot:spring-boot-dependencies:3.5.5'
            }
        }
        """);

    MavenBomVersionResolver resolver = new MavenBomVersionResolver(
        (g, a, v) -> Optional.of(SAMPLE_BOM_POM.getBytes(StandardCharsets.UTF_8)));

    Map<String, String> index = resolver.buildVersionIndex(dir);

    assertThat(index)
        .containsEntry("org.springframework.boot:spring-boot-starter-web", "3.5.5")
        .containsEntry("org.jetbrains.kotlin:kotlin-reflect", "1.9.25");
  }
}
