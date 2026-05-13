package com.salkcoding.oswl.auth.repository;

import com.salkcoding.oswl.auth.entity.SecuritySetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SecuritySettingRepository extends JpaRepository<SecuritySetting, Long> {
}
