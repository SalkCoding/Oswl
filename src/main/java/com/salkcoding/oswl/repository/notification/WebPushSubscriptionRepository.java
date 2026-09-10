package com.salkcoding.oswl.repository.notification;
import com.salkcoding.oswl.domain.entity.notification.WebPushSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.*;
public interface WebPushSubscriptionRepository extends JpaRepository<WebPushSubscription,Long> {
    Optional<WebPushSubscription> findByEndpointHash(String hash);
    List<WebPushSubscription> findByExpiresAtBefore(java.time.LocalDateTime now);
    long countByUserId(Long userId);
    List<WebPushSubscription> findByUserId(Long userId);
}
