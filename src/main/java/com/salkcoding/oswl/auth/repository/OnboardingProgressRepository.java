package com.salkcoding.oswl.auth.repository;

import com.salkcoding.oswl.auth.entity.OnboardingProgress;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingProgressRepository extends JpaRepository<OnboardingProgress, Long> {
}
