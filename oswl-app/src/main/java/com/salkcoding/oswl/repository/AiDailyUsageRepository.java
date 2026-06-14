package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.AiDailyUsage;
import com.salkcoding.oswl.domain.enums.AiProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Optional;

public interface AiDailyUsageRepository extends JpaRepository<AiDailyUsage, Long> {

    Optional<AiDailyUsage> findByUsageDateAndProvider(LocalDate usageDate, AiProvider provider);
}
