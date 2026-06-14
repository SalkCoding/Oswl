package com.salkcoding.oswl.repository;

import org.junit.jupiter.api.Tag;
import com.salkcoding.oswl.testing.TestTags;
import com.salkcoding.oswl.domain.entity.Project;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@Tag(TestTags.INTEGRATION)
@SpringBootTest
@Transactional
@DisplayName("ProjectRepository 통합 테스트")
class ProjectRepositoryTest {

    @Autowired ProjectRepository projectRepository;

    @Test
    @DisplayName("soft-delete 되지 않은 프로젝트만 조회된다")
    void findAllByDeletedAtIsNull_excludesDeletedProjects() {
        projectRepository.save(project("Active"));
        Project deleted = project("Deleted");
        deleted.softDelete();
        projectRepository.save(deleted);

        List<Project> result = projectRepository.findAllByDeletedAtIsNullOrderByCreatedAtDesc();

        assertThat(result).anyMatch(p -> "Active".equals(p.getName()));
        assertThat(result).noneMatch(p -> "Deleted".equals(p.getName()));
    }

    @Test
    @DisplayName("삭제된 프로젝트는 trash 목록에 나온다")
    void findAllByDeletedAtIsNotNull_returnsDeletedProjects() {
        projectRepository.save(project("Active"));
        Project deleted = project("Deleted");
        deleted.softDelete();
        projectRepository.save(deleted);

        List<Project> trash = projectRepository.findAllByDeletedAtIsNotNullOrderByDeletedAtAsc();

        assertThat(trash).anyMatch(p -> "Deleted".equals(p.getName()));
        assertThat(trash).noneMatch(p -> "Active".equals(p.getName()));
    }

    @Test
    @DisplayName("githubRepo로 프로젝트를 찾을 수 있다")
    void findByGithubRepo_returnsMatchingProject() {
        Project p = project("GH-Project");
        p.markGithubImport("owner", "unique-repo-xyz", "main");
        projectRepository.save(p);

        Optional<Project> found = projectRepository.findByGithubRepo("owner/unique-repo-xyz");

        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("GH-Project");
    }

    @Test
    @DisplayName("findByIdAndDeletedAtIsNull은 삭제된 프로젝트를 반환하지 않는다")
    void findByIdAndDeletedAtIsNull_excludesDeleted() {
        Project deleted = project("Will-Be-Deleted");
        deleted.softDelete();
        Project saved = projectRepository.save(deleted);

        Optional<Project> result = projectRepository.findByIdAndDeletedAtIsNull(saved.getId());

        assertThat(result).isEmpty();
    }

    @Test
    @DisplayName("softDelete() 직후에는 30일 만료 기준에 해당 안 됨")
    void findAllByDeletedAtBefore_recentlyDeletedNotExpired() {
        Project recent = project("RecentDeleted");
        recent.softDelete();
        projectRepository.save(recent);

        List<Project> expired = projectRepository.findAllByDeletedAtBefore(LocalDateTime.now().minusDays(30));

        assertThat(expired).noneMatch(p -> "RecentDeleted".equals(p.getName()));
    }

    // ── 헬퍼 ─────────────────────────────────────────────────────────────

    private static Project project(String name) {
        return Project.builder().name(name).build();
    }
}
