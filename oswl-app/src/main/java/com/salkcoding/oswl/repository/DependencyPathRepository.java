package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.DependencyPath;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DependencyPathRepository extends JpaRepository<DependencyPath, Long> {

    /** Retrieve all paths for a component, ordered by their original index. */
    List<DependencyPath> findByScanComponentIdOrderByPathIndexAsc(Long scanComponentId);
}
