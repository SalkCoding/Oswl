package com.salkcoding.oswl.service;

import com.salkcoding.oswl.service.git.CloneRootPathGuard;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Stream;

/**
 * Deletes Quick Import temporary clone directories off the request/job thread.
 *
 * A {@code git clone} of a large repository can leave behind tens of thousands of
 * {@code .git} objects; deleting them synchronously (the previous behavior) held up
 * the job's phase transition to {@code ENRICHING} for no reason the user could see.
 * Deletions are queued here and drained by a single dedicated virtual thread.
 */
@Slf4j
@Service
public class CloneCleanupService {

    @Value("${oswl.clone.temp-dir:}")
    private String configuredTempDir;

    private volatile Path cloneBaseReal;

    private final BlockingQueue<Path> pending = new LinkedBlockingQueue<>();
    private volatile boolean running = true;
    private Thread worker;

    @PostConstruct
    void start() {
        worker = Thread.ofVirtual().name("clone-cleanup-worker").start(this::drainLoop);
        sweepOrphans();
    }

    /** Queues a clone directory for background deletion. Never blocks the caller. */
    public void submit(Path dir) {
        if (dir != null) {
            pending.offer(dir);
        }
    }

    private void drainLoop() {
        while (running) {
            try {
                Path dir = pending.take();
                deleteVerified(dir);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Removes every directory left behind under the clone base — e.g. by a JVM kill mid-clone
     * before this service existed to clean up after itself. Safe only at boot, before any Quick
     * Import job has had a chance to create a new clone directory of its own.
     */
    private void sweepOrphans() {
        try {
            Path base = getCloneBaseReal();
            try (Stream<Path> children = Files.list(base)) {
                children.filter(Files::isDirectory).forEach(dir -> {
                    log.info("[CloneCleanup] Sweeping orphaned clone directory from a previous run: {}", dir);
                    deleteVerified(dir);
                });
            }
        } catch (Exception e) {
            log.warn("[CloneCleanup] Orphan sweep failed: {}", e.getMessage());
        }
    }

    /** Re-verifies the path is still inside the configured clone base before touching disk. */
    private void deleteVerified(Path dir) {
        try {
            Path base = getCloneBaseReal();
            Path real;
            try {
                real = dir.toRealPath(LinkOption.NOFOLLOW_LINKS);
            } catch (IOException notFound) {
                return; // already gone — nothing to do
            }
            if (!real.startsWith(base)) {
                log.warn("[CloneCleanup] Refusing to delete path outside the clone base: {}", dir);
                return;
            }
            deleteRecursively(real);
        } catch (Exception e) {
            log.warn("[CloneCleanup] Could not delete temp dir '{}': {}", dir, e.getMessage());
        }
    }

    private void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.delete(p);
                        } catch (IOException ignored) {
                            // best-effort — a lingering file/lock does not fail the scan
                        }
                    });
        } catch (IOException e) {
            log.warn("[CloneCleanup] Could not delete temp dir '{}': {}", dir, e.getMessage());
        }
    }

    private Path getCloneBaseReal() throws IOException {
        Path base = cloneBaseReal;
        if (base == null) {
            synchronized (this) {
                base = cloneBaseReal;
                if (base == null) {
                    cloneBaseReal = CloneRootPathGuard.resolveConfiguredBase(configuredTempDir);
                    base = cloneBaseReal;
                }
            }
        }
        return base;
    }

    /** Gives queued deletions a short window to finish; anything left is swept on the next boot. */
    @PreDestroy
    void shutdown() {
        running = false;
        long deadline = System.currentTimeMillis() + 5_000;
        while (!pending.isEmpty() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        if (worker != null) {
            worker.interrupt();
        }
    }
}
