package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.ImportSource;
import jakarta.persistence.*;
import lombok.*;

/**
 * Represents a branch-level import of a Project.
 * There is one row per (project, branch) pair, enforced by a unique constraint.
 * Importing a new branch creates a new row with an auto-incremented {@code versionNumber}.
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
}
