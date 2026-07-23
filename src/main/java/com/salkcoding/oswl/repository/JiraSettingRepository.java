package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.JiraSetting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface JiraSettingRepository extends JpaRepository<JiraSetting, Long> {

    /** Single-row settings — the first (and only) row. */
    Optional<JiraSetting> findFirstByOrderByIdAsc();
}
