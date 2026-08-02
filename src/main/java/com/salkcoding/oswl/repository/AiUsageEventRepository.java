package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.ai.AiUsageEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface AiUsageEventRepository extends JpaRepository<AiUsageEvent, Long> {

    Page<AiUsageEvent> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);

    /** Ids of the oldest events — used by the FIFO trim in {@code AiUsageRecorderService}. */
    @Query("select e.id from AiUsageEvent e order by e.createdAt asc, e.id asc")
    List<Long> findOldestIds(Pageable pageable);
}
