package com.salkcoding.oswl.repository.reporting;

import com.salkcoding.oswl.domain.entity.reporting.ReportBrandingSettings;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReportBrandingSettingsRepository extends JpaRepository<ReportBrandingSettings, Long> {

    /** Single-row settings — the first (and only) row. */
    Optional<ReportBrandingSettings> findFirstByOrderByIdAsc();
}
