package com.salkcoding.oswl.repository.ai;

import com.salkcoding.oswl.domain.entity.ai.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AiSettingRepository extends JpaRepository<AiSetting, Long> {

    /**
     * Find the currently active AI setting. A concurrent activate race can briefly commit two
     * active rows (no partial unique index on is_active); return the most recently updated one
     * instead of failing with IncorrectResultSizeDataAccessException until a write path heals it.
     */
    default Optional<AiSetting> findByActiveTrue() {
        return findAllByActiveTrueOrderByUpdatedAtDesc().stream().findFirst();
    }

    /** All active settings, newest first — used to heal duplicate-active race outcomes. */
    List<AiSetting> findAllByActiveTrueOrderByUpdatedAtDesc();

    Optional<AiSetting> findByProvider(AiProvider provider);
}
