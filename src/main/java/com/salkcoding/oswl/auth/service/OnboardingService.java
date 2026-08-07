package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.entity.OnboardingProgress;
import com.salkcoding.oswl.auth.repository.OnboardingProgressRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class OnboardingService {

    private final OnboardingProgressRepository repository;

    @Transactional(readOnly = true)
    public boolean isCompleted() {
        return repository.existsById(OnboardingProgress.SINGLETON_ID);
    }

    /** Idempotent — completing (or skipping to the end of) the wizard twice is a no-op. */
    @Transactional
    public void markCompleted() {
        if (isCompleted()) return;
        try {
            repository.saveAndFlush(OnboardingProgress.create());
        } catch (DataIntegrityViolationException ignored) {
            // Another concurrent request already completed it — fine, same end state.
        }
    }
}
