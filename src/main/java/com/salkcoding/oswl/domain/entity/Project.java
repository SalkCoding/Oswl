package com.salkcoding.oswl.domain.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * An analysis target project registered by the user.
 * CLI scan results are accumulated per this entity.
 */
@Entity
@Table(name = "projects")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Builder
@AllArgsConstructor
public class Project {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String name;

    /** Version as of the current scan */
    @Column(length = 50)
    private String version;

    @Column(name = "last_scanned_at")
    private LocalDateTime lastScannedAt;

    /** GitHub source in "owner/repo#branch" format — null for CLI-imported projects */
    @Column(name = "github_repo", length = 300)
    private String githubRepo;

    /** When this project was first imported from GitHub — null for CLI-imported projects */
    @Column(name = "imported_at")
    private LocalDateTime importedAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ScanResult> scanResults = new ArrayList<>();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL, orphanRemoval = true)
    @Builder.Default
    private List<ApiKey> apiKeys = new ArrayList<>();

    public void updateLastScanned(String version, LocalDateTime scannedAt) {
        this.version = version;
        this.lastScannedAt = scannedAt;
    }

    public void markGithubImport(String owner, String repo, String branch) {
        this.githubRepo = owner + "/" + repo + "#" + branch;
        this.importedAt = LocalDateTime.now();
    }
}
