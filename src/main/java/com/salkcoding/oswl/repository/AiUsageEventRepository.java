package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.AiUsageEvent;
import com.salkcoding.oswl.domain.enums.AiProvider;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

public interface AiUsageEventRepository extends JpaRepository<AiUsageEvent, Long> {

    List<AiUsageEvent> findTop10ByUsageDateOrderByCreatedAtDesc(LocalDate usageDate);

    @Query("""
            select coalesce(sum(e.promptTokens), 0), coalesce(sum(e.completionTokens), 0),
                   coalesce(sum(e.totalTokens), 0), coalesce(sum(e.estimatedCostUsd), 0)
            from AiUsageEvent e
            where e.usageDate = :date and (:provider is null or e.provider = :provider)
            """)
    Object[] sumForDate(@Param("date") LocalDate date, @Param("provider") AiProvider provider);

    @Query("""
            select e.usageDate, coalesce(sum(e.totalTokens), 0), coalesce(sum(e.estimatedCostUsd), 0),
                   count(e)
            from AiUsageEvent e
            where e.usageDate >= :since and (:provider is null or e.provider = :provider)
            group by e.usageDate
            order by e.usageDate desc
            """)
    List<Object[]> dailyTotalsSince(@Param("since") LocalDate since, @Param("provider") AiProvider provider);
}
