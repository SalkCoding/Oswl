package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.exception.UnauthorizedException;
import com.salkcoding.oswl.repository.ApiKeyRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock
    ApiKeyRepository apiKeyRepository;

    @Mock
    ProjectRepository projectRepository;

    @InjectMocks
    ApiKeyService apiKeyService;

    // ── issue ─────────────────────────────────────────────────────────────

    @Test
    @DisplayName("발급된 토큰은 'oswl_' 접두사를 가진다")
    void issue_generatesOswlPrefixedToken() {
        Project project = Project.builder().id(1L).name("P1").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(apiKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiKey result = apiKeyService.issue(1L, "Dev Key", null);

        assertThat(result.getToken()).startsWith("oswl_");
        assertThat(result.getToken()).hasSizeGreaterThan("oswl_".length());
    }

    @Test
    @DisplayName("발급된 키는 active=true이고 label이 설정된다")
    void issue_setsLabelAndActiveState() {
        Project project = Project.builder().id(1L).name("P1").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(apiKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiKey result = apiKeyService.issue(1L, "CI/CD Key", null);

        assertThat(result.getLabel()).isEqualTo("CI/CD Key");
        assertThat(result.isActive()).isTrue();
    }

    @Test
    @DisplayName("만료일이 지정되면 발급된 키에 반영된다")
    void issue_setsExpiresAt_whenProvided() {
        Project project = Project.builder().id(1L).name("P1").build();
        LocalDateTime expiry = LocalDateTime.of(2027, 1, 1, 0, 0);
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(apiKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiKey result = apiKeyService.issue(1L, "Expiring Key", expiry);

        assertThat(result.getExpiresAt()).isEqualTo(expiry);
    }

    @Test
    @DisplayName("프로젝트가 존재하지 않으면 IllegalArgumentException이 발생한다")
    void issue_throwsIllegalArgument_whenProjectNotFound() {
        when(projectRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiKeyService.issue(99L, "label", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("99");
    }

    @Test
    @DisplayName("연속 발급 시 매번 다른 토큰이 생성된다")
    void issue_generatesDifferentTokensEachTime() {
        Project project = Project.builder().id(1L).name("P1").build();
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project));
        when(apiKeyRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ApiKey key1 = apiKeyService.issue(1L, "Key1", null);
        ApiKey key2 = apiKeyService.issue(1L, "Key2", null);

        assertThat(key1.getToken()).isNotEqualTo(key2.getToken());
    }

    // ── validateAndRecord ─────────────────────────────────────────────────

    @Test
    @DisplayName("유효한 토큰으로 검증하면 키를 반환하고 lastUsedAt을 갱신한다")
    void validateAndRecord_returnsKey_andUpdatesLastUsedAt() {
        Project project = Project.builder().id(1L).name("P1").build();
        ApiKey key = ApiKey.builder()
                .id(10L).token("oswl_valid").label("key").active(true).project(project).build();
        when(apiKeyRepository.findByToken("oswl_valid")).thenReturn(Optional.of(key));

        ApiKey result = apiKeyService.validateAndRecord("oswl_valid");

        assertThat(result).isEqualTo(key);
        assertThat(result.getLastUsedAt()).isNotNull();
    }

    @Test
    @DisplayName("존재하지 않는 토큰이면 UnauthorizedException이 발생한다")
    void validateAndRecord_throwsUnauthorized_whenTokenNotFound() {
        when(apiKeyRepository.findByToken("invalid_token")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiKeyService.validateAndRecord("invalid_token"))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("폐기된 키(active=false)이면 UnauthorizedException이 발생한다")
    void validateAndRecord_throwsUnauthorized_whenKeyRevoked() {
        Project project = Project.builder().id(1L).name("P1").build();
        ApiKey revoked = ApiKey.builder()
                .id(11L).token("oswl_revoked").active(false).project(project).build();
        when(apiKeyRepository.findByToken("oswl_revoked")).thenReturn(Optional.of(revoked));

        assertThatThrownBy(() -> apiKeyService.validateAndRecord("oswl_revoked"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("revoked");
    }

    @Test
    @DisplayName("만료된 키이면 UnauthorizedException이 발생한다")
    void validateAndRecord_throwsUnauthorized_whenKeyExpired() {
        Project project = Project.builder().id(1L).name("P1").build();
        ApiKey expired = ApiKey.builder()
                .id(12L).token("oswl_expired").active(true)
                .expiresAt(LocalDateTime.of(2020, 1, 1, 0, 0))  // 과거 시각
                .project(project)
                .build();
        when(apiKeyRepository.findByToken("oswl_expired")).thenReturn(Optional.of(expired));

        assertThatThrownBy(() -> apiKeyService.validateAndRecord("oswl_expired"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("expired");
    }

    // ── revoke ────────────────────────────────────────────────────────────

    @Test
    @DisplayName("프로젝트가 일치하면 키를 폐기(active=false)한다")
    void revoke_deactivatesKey_whenProjectMatches() {
        Project project = Project.builder().id(1L).name("P1").build();
        ApiKey key = ApiKey.builder()
                .id(20L).token("oswl_x").active(true).project(project).build();
        when(apiKeyRepository.findById(20L)).thenReturn(Optional.of(key));

        apiKeyService.revoke(20L, 1L);

        assertThat(key.isActive()).isFalse();
    }

    @Test
    @DisplayName("키가 존재하지 않으면 IllegalArgumentException이 발생한다")
    void revoke_throwsIllegalArgument_whenKeyNotFound() {
        when(apiKeyRepository.findById(99L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> apiKeyService.revoke(99L, 1L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("키가 다른 프로젝트 소속이면 IllegalArgumentException이 발생한다")
    void revoke_throwsIllegalArgument_whenKeyBelongsToOtherProject() {
        Project otherProject = Project.builder().id(2L).name("Other").build();
        ApiKey key = ApiKey.builder()
                .id(21L).token("oswl_y").active(true).project(otherProject).build();
        when(apiKeyRepository.findById(21L)).thenReturn(Optional.of(key));

        assertThatThrownBy(() -> apiKeyService.revoke(21L, 1L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("project");
    }

    // ── findByProject ─────────────────────────────────────────────────────

    @Test
    @DisplayName("프로젝트 ID로 키 목록을 반환한다")
    void findByProject_delegatesToRepository() {
        Project project = Project.builder().id(1L).name("P1").build();
        List<ApiKey> keys = List.of(
                ApiKey.builder().id(1L).token("t1").active(true).project(project).build(),
                ApiKey.builder().id(2L).token("t2").active(true).project(project).build()
        );
        when(apiKeyRepository.findByProjectIdOrderByCreatedAtDesc(1L)).thenReturn(keys);

        assertThat(apiKeyService.findByProject(1L)).hasSize(2);
    }
}
