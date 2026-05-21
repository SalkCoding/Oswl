package com.salkcoding.oswl.domain.entity;

import com.salkcoding.oswl.domain.enums.ImportSource;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Project의 브랜치단위 임포트를 나타낸다.
 * (project, branch) 쌍당 한 행 — 유니크 제약으로 적용된다.
 *
 * 동일 브랜치를 재임포트하면 {@code lastUpdatedAt}이 갱신되며;
 * 새 브랜치를 임포트하면 자동 증가 {@code versionNumber}으로 새 행이 생성된다.
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

    /** Git 브랜치 이름. 예: "main", "develop". */
    @Column(nullable = false, length = 255)
    private String branch;

    /**
     * 부모 프로젝트 내 순새 번호.
     * 첫 번째 브랜치는 1로 시작하며 새 브랜치마다 증가한다.
     */
    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    /** 이 버전이 임포트된 위치. */
    @Enumerated(EnumType.STRING)
    @Column(name = "import_source", nullable = false, length = 10)
    @Builder.Default
    private ImportSource importSource = ImportSource.GIT;

    /** 이 브랜치의 첫 번째 임포트 타임스탬프. */
    @Column(name = "imported_at", nullable = false, updatable = false)
    private LocalDateTime importedAt;

    /** 이 브랜치의 가장 업데이트된 재임포트 타임스탬프. */
    @Column(name = "last_updated_at", nullable = false)
    private LocalDateTime lastUpdatedAt;

    @PrePersist
    private void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (this.importedAt == null) this.importedAt = now;
        if (this.lastUpdatedAt == null) this.lastUpdatedAt = now;
    }

    /** 동일 브랜치를 재임포트할 때 호출하여 업데이트 시간을 기록한다. */
    public void touch() {
        this.lastUpdatedAt = LocalDateTime.now();
    }
}
