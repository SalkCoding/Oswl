package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.ProjectMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;

public interface ProjectMemberRepository extends JpaRepository<ProjectMember, Long> {

    boolean existsByProjectIdAndUserId(Long projectId, Long userId);

    @Query("SELECT pm.project.id FROM ProjectMember pm WHERE pm.userId = :userId")
    List<Long> findProjectIdsByUserId(@Param("userId") Long userId);

    @Query("SELECT pm FROM ProjectMember pm WHERE pm.project.id = :projectId AND pm.userId = :userId")
    List<ProjectMember> findByProjectIdAndUserId(@Param("projectId") Long projectId,
                                                 @Param("userId") Long userId);

    long countByProjectId(Long projectId);

    /** All member user ids of a project (continuous-monitoring alert recipients) */
    @Query("SELECT pm.userId FROM ProjectMember pm WHERE pm.project.id = :projectId")
    List<Long> findUserIdsByProjectId(@Param("projectId") Long projectId);

    @Query("SELECT pm.project.id FROM ProjectMember pm WHERE pm.project.id IN :projectIds AND pm.userId = :userId")
    List<Long> findAccessibleProjectIds(@Param("projectIds") Collection<Long> projectIds,
                                        @Param("userId") Long userId);

    void deleteByUserId(Long userId);
}
