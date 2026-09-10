package com.salkcoding.oswl.service.ingest;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.vulnerability.Library;
import com.salkcoding.oswl.domain.enums.LicenseStatus;
import com.salkcoding.oswl.dto.scan.ScanPayload;
import com.salkcoding.oswl.repository.scan.ScanComponentRepository;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestConstructor;
import org.springframework.transaction.annotation.Transactional;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:nuget-declaration-persistence;DB_CLOSE_DELAY=-1;INIT=CREATE DOMAIN IF NOT EXISTS JSONB AS TEXT",
        "oswl.quick-import.allow-build-exec=false", "oswl.ingest.allow-external-resolution=false"})
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class NuGetDeclarationPersistenceTest {
    private final EntityManager entityManager;
    private final DependencyManifestParserService parser;
    private final ScanIngestService ingest;
    private final ScanComponentRepository components;

    @org.junit.jupiter.api.Test
    @Transactional
    void lockTargetEvidenceSurvivesIngestAndJpaReload(@TempDir Path directory) throws Exception {
        for (int i = 0; i < 10; i++) {
            Path projectDir = Files.createDirectories(directory.resolve("project-" + i));
            Files.writeString(projectDir.resolve("packages.lock.json"), """
                    {"version":1,"dependencies":{"net8.0/win-x64":{"Fixture":{"type":"Direct","resolved":"1.0.0"}}}}
                    """);
        }
        var parsed = parser.parseDependencies(directory, "fixture").components();
        assertThat(parsed).hasSize(1);
        String evidence = parsed.getFirst().getDependencyInfo();
        assertThat(evidence.length()).isGreaterThan(300);
        var project = Project.builder().name("lock-target-persistence").build();
        entityManager.persist(project);
        entityManager.flush();
        Long scanId = ingest.ingest(project.getId(), ScanPayload.create("fixture", parsed)).getId();
        entityManager.flush();
        entityManager.clear();
        var stored = components.findByScanResultId(scanId);
        assertThat(stored).singleElement().satisfies(component -> {
            assertThat(component.getDependencyInfo()).isEqualTo(evidence);
            assertThat(component.getLibrary().getVersion()).isEqualTo("1.0.0");
            assertThat(component.getLibrary().isVulnerabilitiesAnalyzed()).isFalse();
        });
        for (int i = 0; i < 10; i++) assertThat(evidence).contains("project-" + i + "/packages.lock.json");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Transactional
    void parsedDeclarationsSurviveIngestAndJpaReload(boolean existingLibrary, @TempDir Path directory) throws Exception {
        StringBuilder xml = new StringBuilder("<Project>");
        for (int i = 0; i < 10; i++) xml.append("<ItemGroup Condition=\"'$(TargetFramework)' == 'net")
                .append(i).append(".0'\"><PackageReference Include=\"System.Text.Json\" Version=\"8.0.")
                .append(i).append("\" /></ItemGroup>");
        Files.writeString(directory.resolve("App.csproj"), xml.append("</Project>").toString());
        var parsed = parser.parseDependencies(directory, "fixture").components();
        assertThat(parsed).hasSize(1);
        String evidence = parsed.getFirst().getDependencyInfo();
        assertThat(evidence.length()).isGreaterThan(300);

        var project = Project.builder().name("declaration-persistence").build();
        entityManager.persist(project);
        if (existingLibrary) {
            var library = Library.builder().name("System.Text.Json").version(null).ecosystem("NUGET")
                    .licenseStatus(LicenseStatus.UNKNOWN).build();
            entityManager.persist(library);
        }
        entityManager.flush();
        Long scanId = ingest.ingest(project.getId(), ScanPayload.create("fixture", parsed)).getId();
        entityManager.flush();
        entityManager.clear();

        var stored = components.findByScanResultId(scanId);
        assertThat(stored).hasSize(1);
        assertThat(stored.getFirst().getDependencyInfo()).isEqualTo(evidence);
        assertThat(stored.getFirst().getLibrary().getVersion()).isNull();
        assertThat(stored.getFirst().getLibrary().isVulnerabilitiesAnalyzed()).isFalse();
        for (int i = 0; i < 10; i++) assertThat(stored.getFirst().getDependencyInfo())
                .contains("net" + i + ".0", "8.0." + i);
        Long libraryId = stored.getFirst().getLibrary().getId();
        Long secondScanId = ingest.ingest(project.getId(), ScanPayload.create("fixture-next", parsed)).getId();
        entityManager.flush();
        entityManager.clear();
        var second = components.findByScanResultId(secondScanId);
        assertThat(second).hasSize(1);
        assertThat(second.getFirst().getLibrary().getId()).isEqualTo(libraryId);
        assertThat(second.getFirst().getDependencyInfo()).isEqualTo(evidence);
        assertThat(entityManager.createQuery("select count(l) from Library l where l.name = :name and l.ecosystem = 'NUGET' and l.version is null", Long.class)
                .setParameter("name", "System.Text.Json").getSingleResult()).isEqualTo(1L);
    }
}
