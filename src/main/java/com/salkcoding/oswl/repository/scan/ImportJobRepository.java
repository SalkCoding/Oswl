package com.salkcoding.oswl.repository.scan;

import com.salkcoding.oswl.domain.entity.scan.ImportJob;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ImportJobRepository extends JpaRepository<ImportJob, Long> {
    Optional<ImportJob> findByJobId(String jobId);
    List<ImportJob> findByOwnerIdOrderByCreatedAtAsc(Long ownerId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT j FROM ImportJob j WHERE j.jobId = :jobId")
    Optional<ImportJob> lockJob(@Param("jobId") String jobId);
    @Query("SELECT COUNT(j) FROM ImportJob j WHERE j.ownerId = :owner AND j.phase = 'QUEUED' AND j.finishedAt IS NULL")
    long countQueued(@Param("owner") Long owner);
    @Query("SELECT COUNT(j) FROM ImportJob j WHERE j.ownerId = :owner AND j.repoKey = :key AND (j.finishedAt IS NULL OR j.workerActive = true)")
    long countDuplicate(@Param("owner") Long owner, @Param("key") String key);
    long countByWorkerActiveTrue();
    @Query("SELECT j.jobId FROM ImportJob j WHERE j.leaseUntil < :now AND j.finishedAt IS NULL")
    List<String> findExpiredJobIds(@Param("now") Instant now);
    @Modifying
    @Query("UPDATE ImportJob j SET j.leaseUntil = :until WHERE j.workerId = :worker AND (j.finishedAt IS NULL OR j.workerActive = true) AND j.leaseUntil > :now")
    int renew(@Param("worker") String worker, @Param("now") Instant now, @Param("until") Instant until);
    @Modifying
    @Query("UPDATE ImportJob j SET j.workerActive = false WHERE j.leaseUntil < :now")
    int releaseExpiredWorkers(@Param("now") Instant now);
    @Modifying
    @Query("DELETE FROM ImportJob j WHERE j.finishedAt < :cutoff AND j.workerActive = false")
    int deleteFinishedBefore(@Param("cutoff") Instant cutoff);
}
