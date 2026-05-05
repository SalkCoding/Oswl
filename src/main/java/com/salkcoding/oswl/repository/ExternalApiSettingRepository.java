package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.ExternalApiSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ExternalApiSettingRepository extends JpaRepository<ExternalApiSetting, Long> {

    /** Returns the single global settings row (id = 1). */
    Optional<ExternalApiSetting> findFirstByOrderByIdAsc();
}
