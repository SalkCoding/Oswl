package com.salkcoding.oswl.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * Singleton row (id = 1) reserved on first successful /setup.
 * A second concurrent setup fails on insert with a unique-key violation.
 */
@Entity
@Table(name = "instance_setup_lock")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class InstanceSetupLock {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    public static InstanceSetupLock create() {
        InstanceSetupLock lock = new InstanceSetupLock();
        lock.id = SINGLETON_ID;
        lock.completedAt = Instant.now();
        return lock;
    }
}
