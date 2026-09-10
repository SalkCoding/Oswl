package com.salkcoding.oswl.domain.entity.project;

import com.salkcoding.oswl.auth.enums.VcsProvider;
import com.salkcoding.oswl.domain.entity.apikey.ApiKey;
import com.salkcoding.oswl.domain.entity.org.Team;
import com.salkcoding.oswl.domain.entity.scan.ScanResult;
import com.salkcoding.oswl.domain.enums.DeploymentProfile;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

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
        ),
        indexes = {
                // Project list, trash, and auto-cleanup queries all filter on deleted_at.
                @Index(name = "idx_projects_deleted_at", columnList = "deleted_at"),
                // Team-scoped listing + team detail view.
                @Index(name = "idx_projects_team_id", columnList = "team_id")
        })  
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

    /**
     * Git source in "owner/repo" format — null for CLI-imported projects.
     * Unique constraint prevents duplicate projects for the same repository.
     * NOTE: used for any VCS provider (GitHub, GitLab, Bitbucket); name kept for backward compat.
     */
    @Column(name = "github_repo", length = 300)
    private String githubRepo;

    /**
     * VCS provider that owns this repository.
     * Null for CLI-imported projects.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "vcs_provider", length = 32)
    private VcsProvider vcsProvider;

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

    /** Product deployment context for AI triage (nullable → global default from AI preferences). */
    @Enumerated(EnumType.STRING)
    @Column(name = "deployment_profile", length = 40)
    private DeploymentProfile deploymentProfile;

    /**
     * The team that owns this project. Membership of this team grants access to the project
     * (combined with direct {@link ProjectMember} rows by OR in {@code ProjectAccessService}).
     * Existing projects are folded into the "Default" team by the schema migration.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "team_id")
    private Team team;

    /** Free-form comma-separated labels for filtering on the project list (e.g. "backend,pci"). */
    @Column(name = "tags", length = 500)
    private String tags;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

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

    /**
     * Updates denormalized VCS import fields.
     * Called on every upsert (both new and re-import).
     */
    public void markGithubImport(VcsProvider provider, String owner, String repo, String branch) {
        this.vcsProvider = provider;
        this.githubRepo = owner + "/" + repo;
        this.latestBranch = branch;
        this.importedAt = LocalDateTime.now();
    }

    /** Backward-compat overload — defaults to GITHUB. */
    public void markGithubImport(String owner, String repo, String branch) {
        markGithubImport(VcsProvider.GITHUB, owner, repo, branch);
    }

    public void softDelete() {
        this.deletedAt = LocalDateTime.now();
    }

    public void restore() {
        this.deletedAt = null;
    }

    public void updateDeploymentProfile(DeploymentProfile profile) {
        this.deploymentProfile = profile;
    }

    public void assignTeam(Team team) {
        this.team = team;
    }

    public void updateTags(String tags) {
        this.tags = tags;
    }

    /**
     * Tags as a normalized list (trimmed, blanks dropped) for display and filtering.
     * The raw comma-separated form stays in {@link #tags}.
     */
    public List<String> tagList() {
        if (tags == null || tags.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String tag : tags.split(",")) {
            String trimmed = tag.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }
}
