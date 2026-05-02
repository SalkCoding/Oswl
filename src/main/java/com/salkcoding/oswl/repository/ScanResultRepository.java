package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.ScanResult;
import com.salkcoding.oswl.domain.enums.ScanStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.querydsl.QuerydslPredicateExecutor;

import java.util.List;
import java.util.Optional;

public interface ScanResultRepository extends JpaRepository<ScanResult, Long>,
        QuerydslPredicateExecutor<ScanResult> {

    /** 프로젝트의 최신 스캔 결과 1건 */
    Optional<ScanResult> findFirstByProjectIdAndStatusOrderByScannedAtDesc(
            Long projectId, ScanStatus status);

    /** 프로젝트의 완료된 스캔 목록 (버전 히스토리용) */
    @Query("SELECT s FROM ScanResult s WHERE s.project.id = :projectId AND s.status = 'COMPLETED' ORDER BY s.scannedAt DESC")
    List<ScanResult> findCompletedByProjectId(@Param("projectId") Long projectId);

    /** 최근 N개 완료 스캔 (리스크 트렌드 그래프용) */
    @Query(value = """
            SELECT * FROM scan_results
            WHERE project_id = :projectId AND status = 'COMPLETED'
            ORDER BY scanned_at DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<ScanResult> findRecentCompleted(@Param("projectId") Long projectId,
                                         @Param("limit") int limit);
}
