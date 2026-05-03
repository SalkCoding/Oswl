package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** Find a GitHub-imported project by its "owner/repo" key. Used for deduplication. */
    Optional<Project> findByGithubRepo(String githubRepo);

    /** Find a project by its stable CLI identifier (projectUuid). */
    Optional<Project> findByProjectUuid(String projectUuid);
}
