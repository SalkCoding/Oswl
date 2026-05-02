package com.salkcoding.oswl.repository;

import com.salkcoding.oswl.domain.entity.ApiKey;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    /** 토큰 값으로 키를 조회 (인터셉터에서 인증 시 사용) */
    Optional<ApiKey> findByToken(String token);

    /** 프로젝트에 속한 모든 활성 키 조회 */
    @Query("SELECT k FROM ApiKey k WHERE k.project.id = :projectId AND k.active = true ORDER BY k.createdAt DESC")
    List<ApiKey> findActiveByProjectId(@Param("projectId") Long projectId);

    /** 프로젝트에 속한 모든 키 조회 (비활성 포함) */
    List<ApiKey> findByProjectIdOrderByCreatedAtDesc(Long projectId);
}
