package com.salkcoding.oswl.repository.notification;
import com.salkcoding.oswl.domain.entity.notification.WebPushDelivery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.domain.Pageable;
import java.time.LocalDateTime;
import java.util.List;
public interface WebPushDeliveryRepository extends JpaRepository<WebPushDelivery,Long> {
    boolean existsBySubscriptionIdAndEventKey(Long subscriptionId,String eventKey);
    List<WebPushDelivery> findByFinishedFalseAndNextAttemptBeforeOrderByIdAsc(LocalDateTime now,Pageable page);
    void deleteBySubscriptionId(Long subscriptionId);
    void deleteByExpiresAtBefore(LocalDateTime now);
}
