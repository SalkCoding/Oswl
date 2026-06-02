package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** Find a GitHub-imported project by its "owner/repo" key. Used for deduplication. */
    Optional<Project> findByGithubRepo(String githubRepo);

    /** Active projects (not soft-deleted). */
    List<Project> findAllByDeletedAtIsNullOrderByCreatedAtDesc();

    /** Soft-deleted projects (trash). */
    List<Project> findAllByDeletedAtIsNotNullOrderByDeletedAtAsc();

    /** Safe lookup — only finds non-deleted projects. */
    Optional<Project> findByIdAndDeletedAtIsNull(Long id);

    /** Auto-cleanup: projects deleted before the given cutoff. */
    List<Project> findAllByDeletedAtBefore(LocalDateTime cutoff);
}
