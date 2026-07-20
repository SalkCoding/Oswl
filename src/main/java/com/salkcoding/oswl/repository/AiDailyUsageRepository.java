package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.AiDailyUsage;
import com.salkcoding.oswl.domain.enums.AiProvider;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Optional;

public interface AiDailyUsageRepository extends JpaRepository<AiDailyUsage, Long> {

    Optional<AiDailyUsage> findByUsageDateAndProvider(LocalDate usageDate, AiProvider provider);

    /** Row lock for the cap check-then-increment in {@code AiUsageLimiterService} (SELECT ... FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AiDailyUsage u WHERE u.usageDate = :usageDate AND u.provider = :provider")
    Optional<AiDailyUsage> findLockedByUsageDateAndProvider(@Param("usageDate") LocalDate usageDate,
                                                            @Param("provider") AiProvider provider);
}
