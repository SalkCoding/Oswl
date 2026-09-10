package com.salkcoding.oswl.domain.entity.scan;

import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "import_jobs", indexes = {
        @Index(name = "idx_import_jobs_owner", columnList = "owner_id"),
        @Index(name = "idx_import_jobs_lease", columnList = "lease_until")})
@Getter
@Setter
@NoArgsConstructor
public class ImportJob {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Column(name = "job_id", nullable = false, unique = true, length = 36) private String jobId;
    @Column(name = "owner_id", nullable = false) private Long ownerId;
    @Column(name = "repo_key", nullable = false, length = 64) private String repoKey;
    @Column(name = "worker_id", nullable = false, length = 36) private String workerId;
    @Column(nullable = false, length = 20) private String phase;
    @Column(name = "snapshot_json", nullable = false, columnDefinition = "TEXT") private String snapshotJson;
    @Column(name = "lease_until", nullable = false) private Instant leaseUntil;
    @Column(name = "finished_at") private Instant finishedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(nullable = false) private boolean canceled;
    @Column(name = "worker_active", nullable = false) private boolean workerActive;
}
