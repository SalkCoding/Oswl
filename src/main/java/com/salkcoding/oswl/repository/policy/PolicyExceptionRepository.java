package com.salkcoding.oswl.repository.policy;

import com.salkcoding.oswl.domain.entity.policy.PolicyException;
import com.salkcoding.oswl.domain.enums.PolicyExceptionStatus;
import org.springframework.data.jpa.repository.JpaRepository;
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

    List<PolicyException> findByStatusAndExpiryBetween(PolicyExceptionStatus status,
                                                       LocalDateTime start,
                                                       LocalDateTime end);
}
