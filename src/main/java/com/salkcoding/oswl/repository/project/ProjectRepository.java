package com.salkcoding.oswl.repository.project;

import com.salkcoding.oswl.domain.entity.project.Project;
import com.salkcoding.oswl.domain.entity.org.Team;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    /** Find a GitHub-imported project by its "owner/repo" key. Used for deduplication. */
    Optional<Project> findByGithubRepo(String githubRepo);

    /** Active projects (not soft-deleted). */
    List<Project> findAllByDeletedAtIsNullOrderByCreatedAtDesc();

    /**
     * Active projects by id, with the (LAZY) team fetch-joined — the project list page reads
     * {@code team.getName()} for every row, which would otherwise be one extra query per project.
     */
    @Query("SELECT p FROM Project p LEFT JOIN FETCH p.team WHERE p.deletedAt IS NULL AND p.id IN :ids ORDER BY p.createdAt DESC")
    List<Project> findAllByDeletedAtIsNullAndIdInOrderByCreatedAtDesc(@Param("ids") Collection<Long> ids);

    /** Soft-deleted projects (trash). */
    List<Project> findAllByDeletedAtIsNotNullOrderByDeletedAtAsc();

    /** Safe lookup — only finds non-deleted projects. */
    Optional<Project> findByIdAndDeletedAtIsNull(Long id);

    /** Auto-cleanup: projects deleted before the given cutoff. */
    List<Project> findAllByDeletedAtBefore(LocalDateTime cutoff);

    /** Active projects of a team (team drilldown / team detail views). */
    List<Project> findAllByTeamIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long teamId);

    long countByTeamIdAndDeletedAtIsNull(Long teamId);

    /** Project ids the user can reach through a team grant (member of the owning team). */
    @Query("SELECT p.id FROM Project p JOIN TeamMember tm ON tm.team = p.team WHERE tm.userId = :userId")
    List<Long> findProjectIdsByTeamMembership(@Param("userId") Long userId);

    /**
     * Global search: name-matching active projects within the given (accessible) id set.
     * Rows are {@code [id, name]}; the caller caps results via the pageable.
     */
    @Query("""
            SELECT p.id, p.name FROM Project p
            WHERE p.deletedAt IS NULL
              AND p.id IN :ids
              AND LOWER(p.name) LIKE LOWER(CONCAT('%', :q, '%'))
            ORDER BY p.name
            """)
    List<Object[]> searchByIdInAndName(@Param("ids") Collection<Long> ids,
                                       @Param("q") String q,
                                       Pageable pageable);

    /** Reassigns every project of a team — used before the team is deleted. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Project p SET p.team = :newTeam WHERE p.team = :oldTeam")
    int reassignTeam(@Param("oldTeam") Team oldTeam, @Param("newTeam") Team newTeam);

    /** Folds team-less projects into a team — bootstrap counterpart of the schema migration. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Project p SET p.team = :newTeam WHERE p.team IS NULL")
    int assignTeamWhereNull(@Param("newTeam") Team newTeam);
}
