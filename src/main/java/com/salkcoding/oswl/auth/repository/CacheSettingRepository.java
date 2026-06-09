package com.salkcoding.oswl.auth.repository;

import com.salkcoding.oswl.auth.entity.CacheSetting;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CacheSettingRepository extends JpaRepository<CacheSetting, String> {
}
