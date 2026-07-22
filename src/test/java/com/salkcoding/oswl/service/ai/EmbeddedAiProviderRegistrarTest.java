package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.repository.AiSettingRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("EmbeddedAiProviderRegistrar 단위 테스트")
class EmbeddedAiProviderRegistrarTest {

    @Mock AiSettingRepository aiSettingRepository;

    @InjectMocks EmbeddedAiProviderRegistrar registrar;

    @Test
    @DisplayName("LOCAL 설정이 없으면 새로 만들어 활성화한다")
    void register_createsLocalSetting_whenAbsent() {
        when(aiSettingRepository.findByProvider(AiProvider.LOCAL)).thenReturn(Optional.empty());
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.empty());

        registrar.registerAsActiveProvider("gemma-3-1b", "http://localhost:8081/v1");

        ArgumentCaptor<AiSetting> captor = ArgumentCaptor.forClass(AiSetting.class);
        verify(aiSettingRepository).save(captor.capture());
        AiSetting saved = captor.getValue();
        assertThat(saved.getProvider()).isEqualTo(AiProvider.LOCAL);
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getModelName()).isEqualTo("gemma-3-1b");
        assertThat(saved.getBaseUrl()).isEqualTo("http://localhost:8081/v1");
    }

    @Test
    @DisplayName("다른 provider가 활성이면 비활성화하고 LOCAL을 활성화한다")
    void register_deactivatesOtherActiveProvider() {
        AiSetting local = AiSetting.builder().provider(AiProvider.LOCAL).build();
        AiSetting other = AiSetting.builder().provider(AiProvider.OPENAI).build();
        other.activate();
        when(aiSettingRepository.findByProvider(AiProvider.LOCAL)).thenReturn(Optional.of(local));
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(other));

        registrar.registerAsActiveProvider("gemma-3-1b", "http://localhost:8081/v1");

        assertThat(other.isActive()).isFalse();
        assertThat(local.isActive()).isTrue();
        verify(aiSettingRepository).save(other);
        verify(aiSettingRepository).save(local);
    }

    @Test
    @DisplayName("이미 LOCAL이 활성이면 비활성화 없이 갱신만 한다")
    void register_keepsLocalActive_whenAlreadyActive() {
        AiSetting local = AiSetting.builder().provider(AiProvider.LOCAL).build();
        local.activate();
        when(aiSettingRepository.findByProvider(AiProvider.LOCAL)).thenReturn(Optional.of(local));
        when(aiSettingRepository.findByActiveTrue()).thenReturn(Optional.of(local));

        registrar.registerAsActiveProvider("gemma-3-1b", "http://localhost:8081/v1");

        assertThat(local.isActive()).isTrue();
        verify(aiSettingRepository, times(1)).save(local);
    }
}
