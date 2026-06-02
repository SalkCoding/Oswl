package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.exception.ConflictException;
import com.salkcoding.oswl.repository.ApiKeyRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectCliKeyPolicyService")
class ProjectCliKeyPolicyServiceTest {

    @Mock ApiKeyRepository apiKeyRepository;
    @InjectMocks ProjectCliKeyPolicyService policyService;

    private static Project project() {
        return Project.builder().id(1L).name("p").build();
    }

    @Test
    @DisplayName("resolve: 키 없음 → NONE")
    void resolve_noKeys() {
        when(apiKeyRepository.findByProjectIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        assertThat(policyService.resolve(1L)).isEqualTo(ProjectCliKeyPolicyService.ProjectKeyState.NONE);
        assertThat(policyService.canIssueNewKey(1L)).isTrue();
    }

    @Test
    @DisplayName("resolve: 활성 키 있음 → ACTIVE_PRESENT")
    void resolve_activeKey() {
        ApiKey key = ApiKey.builder().id(1L).tokenPrefix("oswl_activeprefix1")
                .tokenHash("hash").active(true).project(project()).build();
        when(apiKeyRepository.findByProjectIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(key));
        assertThat(policyService.resolve(1L)).isEqualTo(ProjectCliKeyPolicyService.ProjectKeyState.ACTIVE_PRESENT);
        assertThat(policyService.canIssueNewKey(1L)).isFalse();
    }

    @Test
    @DisplayName("assertScanIngestAllowed: revoke만 있으면 ConflictException")
    void assertScanIngest_revokedOnly() {
        ApiKey key = ApiKey.builder().id(2L).tokenPrefix("oswl_revokedprefix1")
                .tokenHash("hash").active(false).project(project()).build();
        when(apiKeyRepository.findByProjectIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(key));
        assertThatThrownBy(() -> policyService.assertScanIngestAllowed(1L))
                .isInstanceOf(ConflictException.class);
    }
}
