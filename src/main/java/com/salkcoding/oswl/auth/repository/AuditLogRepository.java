package com.salkcoding.oswl.auth.repository;

import com.salkcoding.oswl.auth.entity.AuditLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {

    @Query("""
            select a from AuditLog a
            where (:start is null or a.createdAt >= :start)
              and (:end is null or a.createdAt <= :end)
              and (:actorEmail is null or lower(a.actorEmail) like lower(concat('%', :actorEmail, '%')))
              and (:action is null or lower(a.action) like lower(concat('%', :action, '%')))
            order by a.createdAt desc
            """)
    Page<AuditLog> search(@Param("start") LocalDateTime start,
                          @Param("end") LocalDateTime end,
                          @Param("actorEmail") String actorEmail,
                          @Param("action") String action,
                          Pageable pageable);

    @Transactional
    @Modifying
    @Query("DELETE FROM AuditLog a WHERE a.createdAt < :cutoff")
    int deleteOlderThan(@Param("cutoff") LocalDateTime cutoff);

    /** Most recent audit log whose primary key is strictly lower than the given id. */
    Optional<AuditLog> findTopByIdLessThanOrderByIdDesc(Long id);

    /** All audit logs matching the optional date range, ordered by primary key ascending (chain order). */
    @Query("""
            select a from AuditLog a
            where (:start is null or a.createdAt >= :start)
              and (:end is null or a.createdAt <= :end)
            order by a.id asc
            """)
    Page<AuditLog> findAllByFilterOrderByIdAsc(@Param("start") LocalDateTime start,
                                                 @Param("end") LocalDateTime end,
                                                 Pageable pageable);
}
