package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.AiDailyUsage;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.dto.api.AiUsageDailyTotalsDto;
import com.salkcoding.oswl.dto.api.AiUsageSumsDto;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface AiDailyUsageRepository extends JpaRepository<AiDailyUsage, Long> {

    Optional<AiDailyUsage> findByUsageDateAndProvider(LocalDate usageDate, AiProvider provider);

    /** Row lock for the cap check-then-increment in {@code AiUsageLimiterService} (SELECT ... FOR UPDATE). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT u FROM AiDailyUsage u WHERE u.usageDate = :usageDate AND u.provider = :provider")
    Optional<AiDailyUsage> findLockedByUsageDateAndProvider(@Param("usageDate") LocalDate usageDate,
                                                            @Param("provider") AiProvider provider);

    @Query("""
            select new com.salkcoding.oswl.dto.api.AiUsageSumsDto(
                coalesce(sum(u.promptTokens), 0), coalesce(sum(u.completionTokens), 0),
                coalesce(sum(u.totalTokens), 0), coalesce(sum(u.estimatedCostUsd), 0))
            from AiDailyUsage u
            where u.usageDate = :date and (:provider is null or u.provider = :provider)
            """)
    AiUsageSumsDto sumForDate(@Param("date") LocalDate date, @Param("provider") AiProvider provider);

    @Query("""
            select new com.salkcoding.oswl.dto.api.AiUsageDailyTotalsDto(
                u.usageDate, coalesce(sum(u.totalTokens), 0), coalesce(sum(u.estimatedCostUsd), 0),
                coalesce(sum(u.callCount), 0))
            from AiDailyUsage u
            where u.usageDate >= :since and (:provider is null or u.provider = :provider)
            group by u.usageDate
            order by u.usageDate desc
            """)
    List<AiUsageDailyTotalsDto> dailyTotalsSince(@Param("since") LocalDate since,
                                                 @Param("provider") AiProvider provider);
}
