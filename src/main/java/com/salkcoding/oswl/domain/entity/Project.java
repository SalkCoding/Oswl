package com.salkcoding.oswl.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An analysis target project registered by the user.
 *
 * One Project row represents a logical project (e.g. one GitHub owner/repo).
 * Branch-level imports are tracked in {@link ProjectVersion}.
 *
 * {@code projectUuid} is a stable, random UUID assigned on creation and serves
 * as the public identifier for CLI integration (e.g. API key parameter).
 */
@Entity
@Table(name = "projects",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_projects_github_repo",
                columnNames = {"github_repo"}
        ))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Stable UUID used as the CLI project identifier / API key.
     * Generated once on first persist and never changes.
     */
    @Column(name = "project_uuid", length = 36, unique = true, updatable = false)
    private String projectUuid;

    @Column(nullable = false, length = 200)
    private String name;

    /** Version as of the current scan */
    @Column(length = 50)
    private String version;

    @Column(name = "last_scanned_at")
    private LocalDateTime lastScannedAt;

    /**
     * GitHub source in "owner/repo" format — null for CLI-imported projects.
     * Unique constraint prevents duplicate projects for the same repository.
     */
    @Column(name = "github_repo", length = 300)
    private String githubRepo;

    /**
     * The most recently imported branch (denormalized for fast card display).
     * Kept in sync by {@link #markGithubImport}.
     */
    @Column(name = "latest_branch", length = 255)
    private String latestBranch;

    /** Timestamp of the most recent GitHub import — updated on every upsert. */
    @Column(name = "imported_at")
    private LocalDateTime importedAt;

    /**
     * The user who first registered this project (Quick Import or manual creation).
     * Null for projects created before user-tracking was introduced, or via CLI.
     */
    @Column(name = "created_by_user_id")
    private Long createdByUserId;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    /** Soft-delete timestamp. Non-null means the project is in the trash. */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ScanResult> scanResults = new ArrayList<>();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ApiKey> apiKeys = new ArrayList<>();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ProjectVersion> versions = new ArrayList<>();

    @PrePersist
    private void generateUuid() {
        if (this.projectUuid == null) {
            this.projectUuid = UUID.randomUUID().toString();
        }
    }

    public void updateLastScanned(String version, LocalDateTime scannedAt) {
        this.version = version;
        this.lastScannedAt = scannedAt;
    }

    /**
     * Updates denormalized GitHub import fields.
     * Called on every upsert (both new and re-import).
     */
    public void markGithubImport(String owner, String repo, String branch) {
        this.githubRepo = owner + "/" + repo;
        this.latestBranch = branch;
        this.importedAt = LocalDateTime.now();
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public void restore() {
        this.deletedAt = null;
    }
}
