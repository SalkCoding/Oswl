package com.salkcoding.oswl.auth.service;

import com.salkcoding.oswl.auth.dto.CacheSettingDto;
import com.salkcoding.oswl.auth.entity.CacheSetting;
import com.salkcoding.oswl.auth.repository.CacheSettingRepository;
import com.salkcoding.oswl.auth.repository.UserRepository;
import com.salkcoding.oswl.auth.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("CacheManagementService 단위 테스트")
class CacheManagementServiceTest {

    @Mock CacheSettingRepository cacheSettingRepository;
    @Mock UserRepository         userRepository;

    @InjectMocks CacheManagementService cacheManagementService;

    // ── findAll ───────────────────────────────────────────────────────────

    @Test
    @DisplayName("findAll: 이미 존재하는 키는 새로 저장하지 않는다")
    void findAll_skipsExistingKeys() {
        when(cacheSettingRepository.existsById("OSV_VULN")).thenReturn(true);
        when(cacheSettingRepository.existsById("DEPS_DEV")).thenReturn(true);
        when(cacheSettingRepository.findAll()).thenReturn(List.of(
                buildCs("OSV_VULN", 604800),
                buildCs("DEPS_DEV", 604800)
        ));

        List<CacheSettingDto> result = cacheManagementService.findAll();

        assertThat(result).hasSize(2);
        verify(cacheSettingRepository, never()).save(any());
    }

    @Test
    @DisplayName("findAll: 누락된 키는 기본 TTL로 저장된다")
    void findAll_initializesMissingKeys() {
        when(cacheSettingRepository.existsById("OSV_VULN")).thenReturn(false);
        when(cacheSettingRepository.existsById("DEPS_DEV")).thenReturn(true);
        when(cacheSettingRepository.findAll()).thenReturn(List.of(
                buildCs("OSV_VULN", 604800),
                buildCs("DEPS_DEV", 604800)
        ));

        cacheManagementService.findAll();

        verify(cacheSettingRepository).save(argThat(cs ->
                "OSV_VULN".equals(cs.getCacheKey()) && cs.getTtlSeconds() == 604800));
    }

    @Test
    @DisplayName("findAll: lastClearedBy가 null이면 lastClearedByName도 null이다")
    void findAll_noLastClearedBy_nullName() {
        when(cacheSettingRepository.existsById(any())).thenReturn(true);
        CacheSetting cs = buildCs("OSV_VULN", 3600);
        // lastClearedBy is null by default
        when(cacheSettingRepository.findAll()).thenReturn(List.of(cs));

        List<CacheSettingDto> dtos = cacheManagementService.findAll();

        assertThat(dtos.get(0).getLastClearedByName()).isNull();
        verify(userRepository, never()).findById(any());
    }

    @Test
    @DisplayName("findAll: lastClearedBy가 설정되면 유저 이름이 DTO에 포함된다")
    void findAll_withLastClearedBy_includesName() {
        when(cacheSettingRepository.existsById(any())).thenReturn(true);
        CacheSetting cs = buildCs("OSV_VULN", 3600);
        cs.setLastClearedBy(42L);
        when(cacheSettingRepository.findAll()).thenReturn(List.of(cs));

        User user = User.builder().id(42L).displayName("Alice").email("alice@test.com")
                .passwordHash("x").enabled(true).isSystemAdmin(false)
                .roleTemplates(new java.util.HashSet<>()).build();
        when(userRepository.findById(42L)).thenReturn(Optional.of(user));

        List<CacheSettingDto> dtos = cacheManagementService.findAll();

        assertThat(dtos.get(0).getLastClearedByName()).isEqualTo("Alice");
    }

    // ── updateTtl ─────────────────────────────────────────────────────────

    @Test
    @DisplayName("updateTtl: TTL이 0 이하이면 IllegalArgumentException을 던진다")
    void updateTtl_zeroOrNegative_throws() {
        assertThatThrownBy(() -> cacheManagementService.updateTtl("OSV_VULN", 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> cacheManagementService.updateTtl("OSV_VULN", -100))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("updateTtl: 기존 CacheSetting이 있으면 TTL을 업데이트한다")
    void updateTtl_existingKey_updates() {
        CacheSetting cs = buildCs("OSV_VULN", 604800);
        when(cacheSettingRepository.findById("OSV_VULN")).thenReturn(Optional.of(cs));

        cacheManagementService.updateTtl("OSV_VULN", 3600);

        assertThat(cs.getTtlSeconds()).isEqualTo(3600);
        verify(cacheSettingRepository).save(cs);
    }

    @Test
    @DisplayName("updateTtl: 키가 없으면 새 CacheSetting을 저장한다")
    void updateTtl_newKey_creates() {
        when(cacheSettingRepository.findById("CUSTOM")).thenReturn(Optional.empty());

        cacheManagementService.updateTtl("CUSTOM", 1800);

        verify(cacheSettingRepository).save(argThat(cs ->
                "CUSTOM".equals(cs.getCacheKey()) && cs.getTtlSeconds() == 1800));
    }

    // ── clearCache ────────────────────────────────────────────────────────

    @Test
    @DisplayName("clearCache: ALL이면 모든 캐시의 lastClearedAt을 갱신한다")
    void clearCache_all_updatesAll() {
        CacheSetting cs1 = buildCs("OSV_VULN", 604800);
        CacheSetting cs2 = buildCs("DEPS_DEV", 604800);
        when(cacheSettingRepository.findAll()).thenReturn(List.of(cs1, cs2));

        cacheManagementService.clearCache("ALL", 1L);

        assertThat(cs1.getLastClearedAt()).isNotNull();
        assertThat(cs2.getLastClearedAt()).isNotNull();
        assertThat(cs1.getLastClearedBy()).isEqualTo(1L);
        verify(cacheSettingRepository).saveAll(List.of(cs1, cs2));
    }

    @Test
    @DisplayName("clearCache: 특정 키가 존재하면 그것만 갱신한다")
    void clearCache_specificKey_updates() {
        CacheSetting cs = buildCs("OSV_VULN", 604800);
        when(cacheSettingRepository.findById("OSV_VULN")).thenReturn(Optional.of(cs));

        cacheManagementService.clearCache("OSV_VULN", 2L);

        assertThat(cs.getLastClearedAt()).isNotNull();
        assertThat(cs.getLastClearedBy()).isEqualTo(2L);
        verify(cacheSettingRepository).save(cs);
    }

    @Test
    @DisplayName("clearCache: 알 수 없는 키이면 IllegalArgumentException을 던진다")
    void clearCache_unknownKey_throws() {
        when(cacheSettingRepository.findById("UNKNOWN")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> cacheManagementService.clearCache("UNKNOWN", 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UNKNOWN");
    }

    @Test
    @DisplayName("clearCache: 'all' 소문자도 전체 캐시를 지운다")
    void clearCache_allLowercase_updatesAll() {
        CacheSetting cs = buildCs("OSV_VULN", 604800);
        when(cacheSettingRepository.findAll()).thenReturn(List.of(cs));

        cacheManagementService.clearCache("all", 1L);

        assertThat(cs.getLastClearedAt()).isNotNull();
    }

    // ── DEFAULT_TTLS ─────────────────────────────────────────────────────

    @Test
    @DisplayName("DEFAULT_TTLS: 기본 TTL 맵에 2개의 키가 있다")
    void defaultTtls_hasTwoKeys() {
        assertThat(CacheManagementService.DEFAULT_TTLS).containsKeys("OSV_VULN", "DEPS_DEV");
        assertThat(CacheManagementService.DEFAULT_TTLS.get("OSV_VULN")).isEqualTo(604800);
    }

    // ── helper ────────────────────────────────────────────────────────────

    private CacheSetting buildCs(String key, long ttl) {
        return CacheSetting.builder().cacheKey(key).ttlSeconds(ttl).build();
    }
}
