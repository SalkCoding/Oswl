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

    @Query("SELECT pm.project.id FROM ProjectMember pm WHERE pm.project.id IN :projectIds AND pm.userId = :userId")
    List<Long> findAccessibleProjectIds(@Param("projectIds") Collection<Long> projectIds,
                                        @Param("userId") Long userId);

    List<ProjectMember> findByProjectIdOrderByCreatedAtAsc(Long projectId);

    void deleteByProjectIdAndUserId(Long projectId, Long userId);

    @Query("SELECT pm FROM ProjectMember pm WHERE pm.project.id = :projectId AND pm.role = :role")
    List<ProjectMember> findByProjectIdAndRole(@Param("projectId") Long projectId,
                                               @Param("role") com.salkcoding.oswl.domain.enums.ProjectMemberRole role);
}
