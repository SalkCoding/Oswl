package com.salkcoding.oswl.repository.org;

import com.salkcoding.oswl.domain.entity.org.TeamMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface TeamMemberRepository extends JpaRepository<TeamMember, Long> {

    boolean existsByTeamIdAndUserId(Long teamId, Long userId);

    Optional<TeamMember> findByTeamIdAndUserId(Long teamId, Long userId);

    List<TeamMember> findByTeamIdOrderByCreatedAtAsc(Long teamId);

    long countByTeamId(Long teamId);

    @Query("SELECT tm.team.id FROM TeamMember tm WHERE tm.userId = :userId")
    List<Long> findTeamIdsByUserId(@Param("userId") Long userId);

    /**
     * Team-grant half of project access resolution: true when the user belongs to the
     * team the project is assigned to. Combined with direct project membership by OR.
     */
    @Query("SELECT CASE WHEN COUNT(tm) > 0 THEN true ELSE false END " +
            "FROM TeamMember tm JOIN Project p ON p.team = tm.team " +
            "WHERE p.id = :projectId AND tm.userId = :userId")
    boolean existsTeamGrantForProject(@Param("projectId") Long projectId, @Param("userId") Long userId);

    void deleteByTeamId(Long teamId);

    void deleteByUserId(Long userId);
}
