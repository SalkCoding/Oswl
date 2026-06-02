package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.CacheSettingDto;
import com.salkcoding.oswl.auth.entity.CacheSetting;
import com.salkcoding.oswl.auth.repository.CacheSettingRepository;
import com.salkcoding.oswl.auth.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CacheManagementService {

    public static final Map<String, Integer> DEFAULT_TTLS;
    static {
        // Default TTL: 7 days (604800 seconds) for all cache keys.
        // Matches the UI default shown in Cache Settings tab.
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("NVD_CVE",  604800);
        m.put("OSV_VULN", 604800);
        m.put("DEPS_DEV", 604800);
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
    public void updateTtl(String cacheKey, long ttlSeconds) {
        if (ttlSeconds <= 0) throw new IllegalArgumentException("TTL must be positive.");
        CacheSetting cs = cacheSettingRepository.findById(cacheKey)
                .orElse(CacheSetting.builder().cacheKey(cacheKey).ttlSeconds(ttlSeconds).build());
        cs.setTtlSeconds(ttlSeconds);
        cacheSettingRepository.save(cs);
        log.info("[Cache] TTL updated key={} ttlSeconds={}", cacheKey, ttlSeconds);
    }

    @Transactional
    public void clearCache(String cacheKey, Long actorUserId) {
        LocalDateTime clearedAt = LocalDateTime.now();
        if ("ALL".equalsIgnoreCase(cacheKey)) {
            List<CacheSetting> all = cacheSettingRepository.findAll();
            all.forEach(cs -> {
                cs.setLastClearedAt(clearedAt);
                cs.setLastClearedBy(actorUserId);
            });
            cacheSettingRepository.saveAll(all);
            log.info("[Cache] Cleared all keys ({} entries) by userId={}", all.size(), actorUserId);
            return;
        }
        CacheSetting cs = cacheSettingRepository.findById(cacheKey)
                .orElseThrow(() -> new IllegalArgumentException("Unknown cache key: " + cacheKey));
        cs.setLastClearedAt(clearedAt);
        cs.setLastClearedBy(actorUserId);
        cacheSettingRepository.save(cs);
        log.info("[Cache] Cleared key={} by userId={}", cacheKey, actorUserId);
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
                .ttlHours(cs.getTtlSeconds() / 3600L)
                .lastClearedAt(cs.getLastClearedAt())
                .lastClearedByName(name)
                .build();
    }
}
