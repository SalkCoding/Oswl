package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.notification.WebhookSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WebhookSettingRepository extends JpaRepository<WebhookSetting, Long> {

    /** Single-row settings — the first (and only) row. */
    Optional<WebhookSetting> findFirstByOrderByIdAsc();
}
