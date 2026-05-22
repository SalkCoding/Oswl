package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.ImportSource;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Represents a branch-level import of a Project.
 * There is one row per (project, branch) pair, enforced by a unique constraint.
 *
 * Re-importing the same branch updates {@code lastUpdatedAt};
 * importing a new branch creates a new row with an auto-incremented {@code versionNumber}.
 */
@Entity
@Table(name = "project_versions",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_project_version_branch",
                columnNames = {"project_id", "branch"}
        ))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class ProjectVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    /** Git branch name. Example: "main", "develop". */
    @Column(nullable = false, length = 255)
    private String branch;

    /**
     * Sequential number within the parent project.
     * The first branch starts at 1 and increments for each new branch.
     */
    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    /** Source from which this version was imported. */
    @Enumerated(EnumType.STRING)
    @Column(name = "import_source", nullable = false, length = 10)
    @Builder.Default
    private ImportSource importSource = ImportSource.GIT;

    /** Timestamp of the first import of this branch. */
    @Column(name = "imported_at", nullable = false, updatable = false)
    private LocalDateTime importedAt;

    /** Timestamp of the most recent re-import of this branch. */
    @Column(name = "last_updated_at", nullable = false)
    private LocalDateTime lastUpdatedAt;

    @PrePersist
    private void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.importedAt == null) this.importedAt = now;
        if (this.lastUpdatedAt == null) this.lastUpdatedAt = now;
    }

    /** Called when re-importing the same branch to record the updated time. */
    public void touch() {
        this.lastUpdatedAt = LocalDateTime.now();
    }
}
