package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.ai.AiPreferences;
import com.salkcoding.oswl.exception.InvalidRequestException;
import com.salkcoding.oswl.repository.ai.AiPreferencesRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("AiPreferencesService 단위 테스트")
class AiPreferencesServiceTest {

    @Mock AiPreferencesRepository repository;
    @Mock AiPromptTemplateService promptTemplateService;

    @InjectMocks AiPreferencesService service;

    @BeforeEach
    void stubExistingRow() {
        lenient().when(repository.findById(AiPreferences.SINGLETON_ID))
                .thenReturn(Optional.of(AiPreferences.defaults("en", 10, 8, "CRITICAL,HIGH", 0)));
    }

    @Test
    @DisplayName("cveSeverities에 유효하지 않은 값이 있으면 InvalidRequestException(400)")
    void save_invalidSeverity_throwsInvalidRequest() {
        assertThatThrownBy(() -> service.save("en", 10, 8, "CRITICAL,FOO", null, null, 0, null, null))
                .isInstanceOf(InvalidRequestException.class)
                .hasMessageContaining("FOO");

        verify(repository, never()).save(any());
    }

    @Test
    @DisplayName("cveSeverities는 대문자로 정규화되어 저장된다")
    void save_validSeverities_normalizedToUpperCase() {
        AiPreferences saved = service.save("en", 10, 8, "critical, high,,HIGH", null, null, 0, null, null);

        assertThat(saved.getCveSeverities()).isEqualTo("CRITICAL,HIGH");
        verify(repository).save(saved);
    }

    @Test
    @DisplayName("cveSeverities가 비어 있으면 기본값 CRITICAL,HIGH")
    void save_blankSeverities_fallsBackToDefault() {
        AiPreferences saved = service.save("en", 10, 8, "  ", null, null, 0, null, null);

        assertThat(saved.getCveSeverities()).isEqualTo("CRITICAL,HIGH");
    }

    @Test void rejectsInvalidModelParametersWithoutSaving() {
        for (double temperature : new double[]{-0.1, 2.1, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertThatThrownBy(() -> service.save("en", 10, 8, "HIGH", temperature, 1200, 0, null, null))
                    .isInstanceOf(InvalidRequestException.class);
        }
        for (int tokens : new int[]{-1, 0, 255, 8193}) {
            assertThatThrownBy(() -> service.save("en", 10, 8, "HIGH", 0.15, tokens, 0, null, null))
                    .isInstanceOf(InvalidRequestException.class);
        }
        verify(repository, never()).save(any());
    }

}
