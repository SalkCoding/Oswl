package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AiSettingRepository extends JpaRepository<AiSetting, Long> {

    /** Find the currently active AI setting */
    Optional<AiSetting> findByActiveTrue();

    Optional<AiSetting> findByProvider(AiProvider provider);
}
