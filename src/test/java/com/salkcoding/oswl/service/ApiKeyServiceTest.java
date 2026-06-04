package com.salkcoding.oswl.service;

import com.salkcoding.oswl.domain.entity.ApiKey;
import com.salkcoding.oswl.domain.entity.Project;
import com.salkcoding.oswl.exception.UnauthorizedException;
import com.salkcoding.oswl.repository.ApiKeyRepository;
import com.salkcoding.oswl.repository.ProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyServiceTest {

    @Mock ApiKeyRepository apiKeyRepository;
    @Mock ProjectRepository projectRepository;
    @Mock com.salkcoding.oswl.auth.service.AuditLogService auditLogService;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private ApiKeyService apiKeyService;

    @BeforeEach
    void setUp() {
        apiKeyService = new ApiKeyService(apiKeyRepository, projectRepository, auditLogService, passwordEncoder);
    }

    private static Project project(long id) {
        return Project.builder().id(id).name("P1").build();
    }

    private ApiKey storedKey(String plainToken, boolean active) {
        return ApiKey.builder()
                .id(10L)
                .tokenPrefix(ApiKeyTokenSupport.extractPrefix(plainToken))
                .tokenHash(passwordEncoder.encode(plainToken))
                .active(active)
                .project(project(1L))
                .build();
    }

    @Test
    @DisplayName("발급된 토큰은 'oswl_' 접두사를 가진다")
    void issue_generatesOswlPrefixedToken() {
        when(projectRepository.findById(1L)).thenReturn(Optional.of(project(1L)));
        when(apiKeyRepository.findByTokenPrefix(any())).thenReturn(Optional.empty());
        when(apiKeyRepository.save(any())).thenAnswer(inv -> {
            ApiKey k = inv.getArgument(0);
            return ApiKey.builder()
                    .id(99L)
                    .project(k.getProject())
                    .tokenPrefix(k.getTokenPrefix())
                    .tokenHash(k.getTokenHash())
                    .label(k.getLabel())
                    .expiresAt(k.getExpiresAt())
                    .build();
        });

        IssuedApiKey issued = apiKeyService.issue(1L, "Dev Key", null);

        assertThat(issued.plainToken()).startsWith("oswl_");
        assertThat(issued.key().getTokenHash()).isNotBlank();
    }

    @Test
    @DisplayName("유효한 토큰으로 검증하면 키를 반환한다")
    void validateAndRecord_validToken() {
        String plain = "oswl_" + "A".repeat(40);
        ApiKey key = storedKey(plain, true);
        when(apiKeyRepository.findByTokenPrefix(ApiKeyTokenSupport.extractPrefix(plain)))
                .thenReturn(Optional.of(key));

        ApiKey result = apiKeyService.validateAndRecord(plain);

        assertThat(result).isEqualTo(key);
        assertThat(result.getLastUsedAt()).isNotNull();
    }

    @Test
    @DisplayName("폐기된 키이면 UnauthorizedException")
    void validateAndRecord_revoked() {
        String plain = "oswl_" + "B".repeat(40);
        ApiKey key = storedKey(plain, false);
        when(apiKeyRepository.findByTokenPrefix(ApiKeyTokenSupport.extractPrefix(plain)))
                .thenReturn(Optional.of(key));

        assertThatThrownBy(() -> apiKeyService.validateAndRecord(plain))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("존재하지 않는 prefix이면 UnauthorizedException")
    void validateAndRecord_unknown() {
        when(apiKeyRepository.findByTokenPrefix(any())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> apiKeyService.validateAndRecord("oswl_" + "C".repeat(40)))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    @DisplayName("findByProject delegates to repository")
    void findByProject() {
        when(apiKeyRepository.findByProjectIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());
        assertThat(apiKeyService.findByProject(1L)).isEmpty();
    }
}
