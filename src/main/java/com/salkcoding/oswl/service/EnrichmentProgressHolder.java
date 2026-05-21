package com.salkcoding.oswl.service;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;

/**
 * 각 ScanResult(scanResultId 키로)에 대한 현재 AI 수정(enrichment) 진행 메시지를
 * 추적하는 인메모리 레지스트리.
 *
 * VulnerabilityEnrichmentService가 진행 레이블을 여기에 쓰고; QuickImportService가
 * 임포트 로그에 실시간 상태를 제공하기 위해 읽는다.
 */
@Component
public class EnrichmentProgressHolder {

    private final ConcurrentHashMap<Long, String> progress = new ConcurrentHashMap<>();

    /** 스캔의 현재 진행 메시지를 업데이트한다. */
    public void set(Long scanResultId, String message) {
        progress.put(scanResultId, message);
    }

    /** 최신 진행 메시지를 가져온다, 추적 중이 아니면 null 반환. */
    public String get(Long scanResultId) {
        return progress.get(scanResultId);
    }

    /** 수정(enrichment)이 완료되면 항목을 제거한다. */
    public void remove(Long scanResultId) {
        progress.remove(scanResultId);
    }
}
