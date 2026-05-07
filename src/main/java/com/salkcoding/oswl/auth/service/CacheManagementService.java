package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.CacheSettingDto;
import com.salkcoding.oswl.auth.entity.CacheSetting;
import com.salkcoding.oswl.auth.repository.CacheSettingRepository;
import com.salkcoding.oswl.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CacheManagementService {

    public static final Map<String, Integer> DEFAULT_TTLS;
    static {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("NVD_CVE", 86400);
        m.put("OSV_VULN", 43200);
        m.put("DEPS_DEV", 172800);
        DEFAULT_TTLS = Map.copyOf(m);
    }

    private final CacheSettingRepository cacheSettingRepository;
    private final UserRepository userRepository;

    @Transactional
    public List<CacheSettingDto> findAll() {
        // Initialize defaults if missing
        for (Map.Entry<String, Integer> e : DEFAULT_TTLS.entrySet()) {
            if (!cacheSettingRepository.existsById(e.getKey())) {
                cacheSettingRepository.save(CacheSetting.builder()
                        .cacheKey(e.getKey())
                        .ttlSeconds(e.getValue())
                        .build());
            }
        }
        return cacheSettingRepository.findAll().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public void updateTtl(String cacheKey, int ttlSeconds) {
        if (ttlSeconds <= 0) throw new IllegalArgumentException("TTL must be positive.");
        CacheSetting cs = cacheSettingRepository.findById(cacheKey)
                .orElse(CacheSetting.builder().cacheKey(cacheKey).ttlSeconds(ttlSeconds).build());
        cs.setTtlSeconds(ttlSeconds);
        cacheSettingRepository.save(cs);
    }

    @Transactional
    public void clearCache(String cacheKey, Long actorUserId) {
        if ("ALL".equalsIgnoreCase(cacheKey)) {
            cacheSettingRepository.findAll().forEach(cs -> {
                cs.setLastClearedAt(LocalDateTime.now());
                cs.setLastClearedBy(actorUserId);
            });
            return;
        }
        CacheSetting cs = cacheSettingRepository.findById(cacheKey)
                .orElseThrow(() -> new IllegalArgumentException("알 수 없는 캐시 키: " + cacheKey));
        cs.setLastClearedAt(LocalDateTime.now());
        cs.setLastClearedBy(actorUserId);
        // Note: actual cache eviction (e.g. Spring Cache, Caffeine) hooks here in a future change.
    }

    private CacheSettingDto toDto(CacheSetting cs) {
        String name = null;
        if (cs.getLastClearedBy() != null) {
            name = userRepository.findById(cs.getLastClearedBy())
                    .map(u -> u.getDisplayName())
                    .orElse(null);
        }
        return CacheSettingDto.builder()
                .cacheKey(cs.getCacheKey())
                .ttlSeconds(cs.getTtlSeconds())
                .ttlHours(cs.getTtlSeconds() / 3600)
                .lastClearedAt(cs.getLastClearedAt())
                .lastClearedByName(name)
                .build();
    }
}
