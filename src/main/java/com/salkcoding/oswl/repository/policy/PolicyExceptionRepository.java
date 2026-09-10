package com.salkcoding.oswl.repository.policy;

import com.salkcoding.oswl.domain.entity.policy.PolicyException;
import com.salkcoding.oswl.domain.enums.PolicyExceptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface PolicyExceptionRepository extends JpaRepository<PolicyException, Long> {

    List<PolicyException> findByProjectIdOrderByCreatedAtDesc(Long projectId);

    List<PolicyException> findByProjectIdAndStatusOrderByCreatedAtDesc(Long projectId,
                                                                        PolicyExceptionStatus status);

    List<PolicyException> findByStatusAndExpiryLessThanEqual(PolicyExceptionStatus status,
                                                             LocalDateTime now);

    /**
     * System-wide, not scoped to a project — POLICY_MANAGE is a global permission.
     * Mobile approval view; project fetch-joined since every row displays the project name.
     */
    @Query("SELECT e FROM PolicyException e JOIN FETCH e.project WHERE e.status = :status ORDER BY e.createdAt DESC")
    List<PolicyException> findByStatusOrderByCreatedAtDesc(@Param("status") PolicyExceptionStatus status);

    List<PolicyException> findByStatusAndExpiryBetween(PolicyExceptionStatus status,
                                                       LocalDateTime start,
                                                       LocalDateTime end);
}
