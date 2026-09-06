package com.salkcoding.oswl.service.ingest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.salkcoding.oswl.domain.entity.scan.ImportCoordinator;
import com.salkcoding.oswl.domain.entity.scan.ImportJob;
import com.salkcoding.oswl.dto.QuickImportJobStatus;
import com.salkcoding.oswl.dto.QuickImportJobStatus.Phase;
import com.salkcoding.oswl.dto.QuickImportMessageKeys;
import com.salkcoding.oswl.exception.QuickImportDuplicateException;
import com.salkcoding.oswl.exception.QuickImportQueueFullException;
import com.salkcoding.oswl.repository.scan.ImportCoordinatorRepository;
import com.salkcoding.oswl.repository.scan.ImportJobRepository;
import com.salkcoding.oswl.repository.scan.ScanResultRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/** Durable owner/lease/state boundary. Never persists API tokens or repository credentials.
 * Expired work is terminated, not replayed: clone/ingest side effects are not retry-safe. */
@Service
@RequiredArgsConstructor
public class ImportJobStore {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Duration LEASE = Duration.ofMinutes(2);
    private final ImportJobRepository jobs;
    private final ImportCoordinatorRepository coordinator;
    private final ScanResultRepository scans;
    private final JdbcTemplate jdbc;
    private final String workerId = UUID.randomUUID().toString();
    private final Set<String> pendingReleases = ConcurrentHashMap.newKeySet();

    @PostConstruct
    void initialize() {
        if (!coordinator.existsById(1L)) {
            try { coordinator.saveAndFlush(new ImportCoordinator()); }
            catch (org.springframework.dao.DataIntegrityViolationException e) {
                if (!coordinator.existsById(1L)) throw e;
            }
        }
    }
    private Instant now() {
        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", (rs, row) -> rs.getTimestamp(1).toInstant());
    }
    private void lock() {
        if (coordinator.lockCoordinator() == null) throw new IllegalStateException("Import coordinator is missing");
    }

    @Transactional
    public void reserve(QuickImportJobStatus status, Long owner, String repoKey, int cap, boolean localBatch) {
        lock();
        String key = hash(repoKey);
        if (!localBatch && jobs.countQueued(owner) >= cap) throw new QuickImportQueueFullException(cap);
        if (!localBatch && jobs.countDuplicate(owner, key) > 0) throw new QuickImportDuplicateException(status.getRepoLabel());
        Instant now = now();
        ImportJob job = new ImportJob();
        job.setJobId(status.getJobId()); job.setOwnerId(owner); job.setRepoKey(key); job.setWorkerId(workerId);
        job.setCreatedAt(now); job.setLeaseUntil(now.plus(LEASE));
        write(job, status, now);
        jobs.save(job);
    }

    @Transactional
    public boolean claim(String id, int maxWorkers) {
        lock();
        ImportJob job = jobs.lockJob(id).orElse(null);
        if (job == null || job.isCanceled() || job.getFinishedAt() != null || !workerId.equals(job.getWorkerId())) return false;
        if (!job.getLeaseUntil().isAfter(now()) || jobs.countByWorkerActiveTrue() >= maxWorkers) return false;
        if (job.isWorkerActive()) return false;
        job.setWorkerActive(true);
        return true;
    }

    @Transactional
    public void release(String id) {
        jobs.lockJob(id).filter(j -> workerId.equals(j.getWorkerId())).ifPresent(j -> j.setWorkerActive(false));
    }

    public void retryRelease(String id) { pendingReleases.add(id); }

    @Transactional
    public void publish(QuickImportJobStatus status) {
        ImportJob job = jobs.lockJob(status.getJobId()).orElse(null);
        if (job == null || !workerId.equals(job.getWorkerId()) || job.isCanceled()) return;
        Instant now = now();
        if (job.getFinishedAt() != null) {
            // AI status may advance after scan completion, but a terminal phase cannot regress.
            if (!"DONE".equals(job.getPhase()) || status.getPhase() != Phase.DONE) return;
        } else if (!job.getLeaseUntil().isAfter(now)) return;
        if (status.getPhase().ordinal() < Phase.valueOf(job.getPhase()).ordinal()) return;
        write(job, status, now);
    }

    @Transactional
    public boolean cancel(String id, Long owner) {
        ImportJob job = jobs.lockJob(id).orElse(null);
        if (job == null || !job.getOwnerId().equals(owner) || job.getFinishedAt() != null) return false;
        job.setCanceled(true);
        write(job, decode(job).toBuilder().phase(Phase.FAILED).messageKey(QuickImportMessageKeys.CANCELED)
                .message(null).messageArgs(List.of()).queuePosition(null).build(), now());
        return true;
    }

    @Transactional(readOnly = true)
    public QuickImportJobStatus read(String id, Long owner) {
        return jobs.findByJobId(id).filter(j -> j.getOwnerId().equals(owner)).map(this::decode).orElse(null);
    }
    @Transactional(readOnly = true)
    public List<QuickImportJobStatus> list(Long owner) {
        return jobs.findByOwnerIdOrderByCreatedAtAsc(owner).stream().map(this::decode).toList();
    }

    @Transactional(readOnly = true)
    public boolean ownsLease(String id) {
        Instant now = now();
        return jobs.findByJobId(id).filter(j -> workerId.equals(j.getWorkerId()) && !j.isCanceled()
                && j.getFinishedAt() == null && j.getLeaseUntil().isAfter(now)).isPresent();
    }

    /** Keep the job lock through the short database ingest transaction; recovery/cancel cannot
     * race the commit. No clone, network call or source traversal belongs inside this callback. */
    @Transactional
    public com.salkcoding.oswl.domain.entity.scan.ScanResult fenced(String id,
            Supplier<com.salkcoding.oswl.domain.entity.scan.ScanResult> action) {
        ImportJob job = jobs.lockJob(id).orElseThrow();
        if (!workerId.equals(job.getWorkerId()) || job.isCanceled() || job.getFinishedAt() != null
                || !job.getLeaseUntil().isAfter(now())) throw new IllegalStateException("Import worker lease expired");
        QuickImportJobStatus prior = decode(job);
        if (prior.getScanResultId() != null) throw new IllegalStateException("Import scan already committed");
        var scan = action.get();
        write(job, prior.toBuilder().scanResultId(scan.getId()).build(), now());
        return scan;
    }

    @Transactional(readOnly = true)
    public boolean hasActiveSourceScan(Long scanId) {
        Instant current = now();
        return jobs.findByWorkerActiveTrue().stream().anyMatch(job -> job.getLeaseUntil().isAfter(current)
                && scanId.equals(decode(job).getScanResultId()));
    }

    @Transactional(readOnly = true)
    public int activeWorkers() { return Math.toIntExact(jobs.countByWorkerActiveTrue()); }

    @Transactional
    public void maintain() {
        lock();
        Set<String> releasing = Set.copyOf(pendingReleases);
        releasing.forEach(this::release);
        // Clear retries only after commit: a transient DB failure must not leak a live
        // worker slot indefinitely while this process continues renewing its leases.
        if (!releasing.isEmpty()) org.springframework.transaction.support.TransactionSynchronizationManager
                .registerSynchronization(new org.springframework.transaction.support.TransactionSynchronization() {
                    @Override public void afterCommit() { pendingReleases.removeAll(releasing); }
                });
        Instant now = now();
        jobs.renew(workerId, now, now.plus(LEASE));
        for (String expired : jobs.findExpiredJobIds(now)) {
            ImportJob job = jobs.lockJob(expired).orElseThrow();
            if (job.getLeaseUntil().isAfter(now)) continue;
            QuickImportJobStatus snapshot = decode(job);
            var scan = snapshot.getScanResultId() == null ? null : scans.lockForSourceWrite(snapshot.getScanResultId()).orElse(null);
            boolean completed = scan != null && scan.getStatus() == com.salkcoding.oswl.domain.enums.ScanStatus.COMPLETED;
            if (scan != null && !completed && jobs.findActiveJobs().stream().noneMatch(active ->
                    active.getLeaseUntil().isAfter(now) && scan.getId().equals(decode(active).getScanResultId()))) {
                scan.fail("Import worker lease expired before analysis completed");
                scans.save(scan);
            }
            if (job.getFinishedAt() == null) write(job, snapshot.toBuilder().phase(completed ? Phase.DONE : Phase.FAILED)
                    .messageKey(completed ? QuickImportMessageKeys.IMPORT_COMPLETE : "interrupted")
                    .message(null).error(null).messageArgs(List.of(String.valueOf(snapshot.getComponentCount())))
                    .queuePosition(null).build(), now);
        }
        jobs.releaseExpiredWorkers(now);
        jobs.deleteFinishedBefore(now.minus(Duration.ofMinutes(30)));
    }

    private void write(ImportJob job, QuickImportJobStatus status, Instant now) {
        job.setPhase(status.getPhase().name());
        try { job.setSnapshotJson(JSON.writeValueAsString(status.toBuilder().apiToken(null).build())); }
        catch (Exception e) { throw new IllegalStateException("Cannot serialize import status", e); }
        if (job.getFinishedAt() == null && (status.getPhase() == Phase.DONE || status.getPhase() == Phase.FAILED)) job.setFinishedAt(now);
    }
    private QuickImportJobStatus decode(ImportJob job) {
        try { return JSON.readValue(job.getSnapshotJson(), QuickImportJobStatus.class); }
        catch (Exception e) { throw new IllegalStateException("Cannot read import status", e); }
    }
    private static String hash(String key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
