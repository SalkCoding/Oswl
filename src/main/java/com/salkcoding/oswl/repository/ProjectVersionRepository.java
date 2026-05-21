package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.domain.entity.ProjectVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface ProjectVersionRepository extends JpaRepository<ProjectVersion, Long> {

    /** 특정 프로젝트의 특정 브랜치 버전 레코드를 반환한다. */
    Optional<ProjectVersion> findByProjectAndBranch(Project project, String branch);

    /**
     * 프로젝트에 이미 할당된 가장 높은 버전 번호를 반환한다.
     * 버전이 없으면 0을 반환한다.
     */
    @Query("SELECT COALESCE(MAX(v.versionNumber), 0) FROM ProjectVersion v WHERE v.project = :project")
    int findMaxVersionNumber(@Param("project") Project project);
}
