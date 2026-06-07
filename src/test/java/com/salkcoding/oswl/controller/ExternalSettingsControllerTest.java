package com.salkcoding.oswl.controller;

import com.salkcoding.oswl.domain.entity.ExternalApiSetting;
import com.salkcoding.oswl.repository.ExternalApiSettingRepository;
import com.salkcoding.oswl.service.ExternalApiSettingSecretsService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExternalSettingsController 단위 테스트")
class ExternalSettingsControllerTest {

    @Mock ExternalApiSettingRepository externalApiSettingRepository;
    @Mock ExternalApiSettingSecretsService externalApiSettingSecretsService;

    @InjectMocks ExternalSettingsController controller;

    @org.junit.jupiter.api.BeforeEach
    void stubEncryption() {
        lenient().when(externalApiSettingSecretsService.encryptSecret(any())).thenAnswer(inv -> {
            String plain = inv.getArgument(0);
            return plain == null || plain.isBlank() ? null : "enc:" + plain;
        });
    }

    private ExternalApiSetting defaultSetting() {
        return ExternalApiSetting.builder().build();
    }

    // ── getSettings ───────────────────────────────────────────────────────

    @Test
    @DisplayName("getSettings: 기존 설정 없음 → 기본값 생성 후 반환")
    void getSettings_noExisting_createsDefaultAndReturns() {
        ExternalApiSetting created = defaultSetting();
        when(externalApiSettingRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.empty());
        when(externalApiSettingRepository.save(any())).thenReturn(created);

        ResponseEntity<Map<String, Object>> resp = controller.getSettings();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody()).containsKey("permanentCache");
        assertThat(resp.getBody()).containsKey("cacheTtlDays");
    }

    @Test
    @DisplayName("getSettings: cacheTtlDays null → 0 반환")
    void getSettings_cacheTtlDaysNull_returnsZero() {
        ExternalApiSetting setting = ExternalApiSetting.builder().build();
        when(externalApiSettingRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(setting));

        ResponseEntity<Map<String, Object>> resp = controller.getSettings();

        assertThat(resp.getBody().get("cacheTtlDays")).isEqualTo(0);
    }

    // ── updateCache ───────────────────────────────────────────────────────

    @Test
    @DisplayName("updateCache: permanentCache=true → ttlDays null")
    void updateCache_permanent_setsPermanent() {
        ExternalApiSetting setting = defaultSetting();
        when(externalApiSettingRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(setting));
        when(externalApiSettingRepository.save(any())).thenReturn(setting);

        Map<String, Object> body = Map.of("permanentCache", true);
        ResponseEntity<Map<String, Object>> resp = controller.updateCache(body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("permanentCache")).isEqualTo(true);
    }

    @Test
    @DisplayName("updateCache: permanentCache=false, cacheTtlDays=30 → TTL 설정")
    void updateCache_nonPermanent_setsTtl() {
        ExternalApiSetting setting = defaultSetting();
        when(externalApiSettingRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(setting));
        when(externalApiSettingRepository.save(any())).thenReturn(setting);

        Map<String, Object> body = Map.of("permanentCache", false, "cacheTtlDays", 30);
        ResponseEntity<Map<String, Object>> resp = controller.updateCache(body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("permanentCache")).isEqualTo(false);
        assertThat(resp.getBody().get("cacheTtlDays")).isEqualTo(30);
    }

    @Test
    @DisplayName("updateCache: cacheTtlDays가 Number가 아님 → ttlDays=null")
    void updateCache_nonNumberTtl_ignoresTtl() {
        ExternalApiSetting setting = defaultSetting();
        when(externalApiSettingRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(setting));
        when(externalApiSettingRepository.save(any())).thenReturn(setting);

        Map<String, Object> body = Map.of("permanentCache", false, "cacheTtlDays", "invalid");
        ResponseEntity<Map<String, Object>> resp = controller.updateCache(body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    // ── getGithubSettings ─────────────────────────────────────────────────

    @Test
    @DisplayName("getGithubSettings: GitHub OAuth 미설정 → configured=false")
    void getGithubSettings_notConfigured_returnsFalse() {
        ExternalApiSetting setting = defaultSetting();
        when(externalApiSettingRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(setting));

        ResponseEntity<Map<String, Object>> resp = controller.getGithubSettings();

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("configured")).isEqualTo(false);
    }

    // ── updateGithubSettings ──────────────────────────────────────────────

    @Test
    @DisplayName("updateGithubSettings: clientId, secret, redirectUri 업데이트")
    void updateGithubSettings_updatesAllFields() {
        ExternalApiSetting setting = defaultSetting();
        when(externalApiSettingRepository.findFirstByOrderByIdAsc()).thenReturn(Optional.of(setting));
        when(externalApiSettingRepository.save(any())).thenReturn(setting);

        Map<String, String> body = Map.of(
                "clientId", "gh-client-id",
                "clientSecret", "gh-client-secret",
                "redirectUri", "https://example.com/callback"
        );
        ResponseEntity<Map<String, Object>> resp = controller.updateGithubSettings(body);

        assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(resp.getBody().get("configured")).isEqualTo(true);
        verify(externalApiSettingRepository).save(setting);
    }
}
