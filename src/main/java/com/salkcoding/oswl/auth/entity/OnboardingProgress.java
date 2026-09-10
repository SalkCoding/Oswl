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
 * Singleton row (id = 1) — set once the post-setup onboarding wizard is finished or dismissed.
 * Tracked separately from {@link InstanceSetupLock}: setup creates the first admin account,
 * onboarding is the guided tour after that (SSO/invites, repo connection, policy defaults,
 * notifications) — skippable, and re-runnable from Settings even after completion.
 */
@Entity
@Table(name = "onboarding_progress")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OnboardingProgress {

    public static final long SINGLETON_ID = 1L;

    @Id
    private Long id;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    public static OnboardingProgress create() {
        OnboardingProgress progress = new OnboardingProgress();
        progress.id = SINGLETON_ID;
        progress.completedAt = Instant.now();
        return progress;
    }
}
