package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ProjectVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProjectVersionRepository extends JpaRepository<ProjectVersion, Long> {

    /** Find the version record for a specific branch of a project. */
    Optional<ProjectVersion> findByProjectAndBranch(Project project, String branch);

    /**
     * Returns the highest version number already assigned to the project,
     * or 0 if no versions exist yet.
     */
    @Query("SELECT COALESCE(MAX(v.versionNumber), 0) FROM ProjectVersion v WHERE v.project = :project")
    int findMaxVersionNumber(@Param("project") Project project);
}
