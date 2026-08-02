package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.WebhookDelivery;
import com.salkcoding.oswl.domain.enums.WebhookDeliveryStatus;
import com.salkcoding.oswl.domain.enums.WebhookEventType;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface WebhookDeliveryRepository extends JpaRepository<WebhookDelivery, Long> {

    List<WebhookDelivery> findByStatusOrderByCreatedAtDesc(WebhookDeliveryStatus status, Pageable pageable);

    List<WebhookDelivery> findByEventTypeOrderByCreatedAtDesc(WebhookEventType eventType, Pageable pageable);

    List<WebhookDelivery> findByProjectIdOrderByCreatedAtDesc(Long projectId, Pageable pageable);
}
