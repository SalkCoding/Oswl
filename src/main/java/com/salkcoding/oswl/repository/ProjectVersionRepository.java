package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.project.ProjectVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProjectVersionRepository extends JpaRepository<ProjectVersion, Long> {

    /** Returns the version record for a specific branch of a specific project. */
    Optional<ProjectVersion> findByProjectAndBranch(Project project, String branch);

    /** Batch lookup of version records for many branches of one project (scan history page). */
    List<ProjectVersion> findByProjectAndBranchIn(Project project, Collection<String> branches);

    /**
     * Returns the highest version number already assigned to the project.
     * Returns 0 if there are no versions.
     */
    @Query("SELECT COALESCE(MAX(v.versionNumber), 0) FROM ProjectVersion v WHERE v.project = :project")
    int findMaxVersionNumber(@Param("project") Project project);
}
