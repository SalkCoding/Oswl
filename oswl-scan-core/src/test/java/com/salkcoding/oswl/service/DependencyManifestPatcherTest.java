package com.salkcoding.oswl.service;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.PARSER)
@DisplayName("DependencyManifestPatcher 단위 테스트")
class DependencyManifestPatcherTest {

    @Test
    @DisplayName("package.json: 패키지명 기준으로 버전을 올린다")
    void patch_packageJson_bumpsByPackageName() {
        String json = """
                {
                  "dependencies": {
                    "minimist": "1.2.5"
                  }
                }
                """;
        Optional<String> out = DependencyManifestPatcher.patch(json, "package.json", "minimist", "1.2.5", "1.2.6");
        assertThat(out).isPresent();
        assertThat(out.get()).contains("\"minimist\": \"1.2.6\"");
        assertThat(out.get()).doesNotContain("1.2.5");
    }

    @Test
    @DisplayName("package.json: 캐럿 범위 접두사를 유지한다")
    void patch_packageJson_preservesRangePrefix() {
        String json = "\"minimist\": \"^1.2.5\"";
        Optional<String> out = DependencyManifestPatcher.patch(json, "package.json", "minimist", "1.2.5", "1.2.6");
        assertThat(out).isPresent();
        assertThat(out.get()).isEqualTo("\"minimist\": \"^1.2.6\"");
    }

    @Test
    @DisplayName("pom.xml: artifactId 인접 version 태그를 수정한다")
    void patch_pomXml_bumpsNearArtifact() {
        String pom = """
                <dependency>
                  <artifactId>minimist</artifactId>
                  <version>1.2.5</version>
                </dependency>
                """;
        Optional<String> out = DependencyManifestPatcher.patch(pom, "pom.xml", "minimist", "1.2.5", "1.2.6");
        assertThat(out).isPresent();
        assertThat(out.get()).contains("<version>1.2.6</version>");
    }

    @Test
    @DisplayName("다른 패키지만 있으면 빈 결과를 반환한다")
    void patch_noMatch_returnsEmpty() {
        String json = "\"lodash\": \"4.17.21\"";
        assertThat(DependencyManifestPatcher.patch(json, "package.json", "minimist", "1.2.5", "1.2.6"))
                .isEmpty();
    }

    @Test
    @DisplayName("isManifestPath: 하위 디렉터리의 package.json을 인식한다")
    void isManifestPath_nestedPackageJson() {
        assertThat(DependencyManifestPatcher.isManifestPath("frontend/package.json")).isTrue();
        assertThat(DependencyManifestPatcher.isManifestPath("README.md")).isFalse();
    }

    @Test
    @DisplayName("package-lock v3: handlebars transitive node_modules/minimist 항목을 올린다")
    void patch_lockfileV3_transitiveMinimist() {
        String lock = """
                {
                  "packages": {
                    "node_modules/handlebars": {
                      "dependencies": {
                        "minimist": "^1.2.5"
                      }
                    },
                    "node_modules/minimist": {
                      "version": "1.2.5",
                      "resolved": "https://registry.npmjs.org/minimist/-/minimist-1.2.5.tgz"
                    }
                  }
                }
                """;
        Optional<String> out = DependencyManifestPatcher.patch(lock, "package-lock.json", "minimist", "1.2.5", "1.2.6");
        assertThat(out).isPresent();
        assertThat(out.get()).contains("\"node_modules/minimist\"");
        assertThat(out.get()).contains("\"version\": \"1.2.6\"");
        assertThat(out.get()).doesNotContain("\"version\": \"1.2.5\"");
        assertThat(out.get()).contains("\"minimist\": \"^1.2.6\"");
    }

    @Test
    @DisplayName("package-lock v3: 중첩 node_modules/handlebars/node_modules/minimist 만 있어도 패치한다")
    void patch_lockfileV3_nestedMinimistOnly() {
        String lock = """
                {
                  "packages": {
                    "node_modules/handlebars": {
                      "dependencies": {
                        "minimist": "^1.2.5"
                      }
                    },
                    "node_modules/handlebars/node_modules/minimist": {
                      "version": "1.2.5",
                      "resolved": "https://registry.npmjs.org/minimist/-/minimist-1.2.5.tgz"
                    }
                  }
                }
                """;
        Optional<String> out = DependencyManifestPatcher.patch(lock, "package-lock.json", "minimist", "1.2.5", "1.2.6");
        assertThat(out).isPresent();
        assertThat(out.get()).contains("node_modules/handlebars/node_modules/minimist");
        assertThat(out.get()).contains("\"version\": \"1.2.6\"");
        assertThat(out.get()).doesNotContain("\"version\": \"1.2.5\"");
    }
}
