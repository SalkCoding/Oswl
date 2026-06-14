package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.NotificationSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationSettingRepository extends JpaRepository<NotificationSetting, Long> {
}
