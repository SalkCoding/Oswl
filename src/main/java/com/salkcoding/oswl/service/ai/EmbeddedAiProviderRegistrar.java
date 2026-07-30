package com.salkcoding.oswl.service.ai;

import com.salkcoding.oswl.domain.entity.AiSetting;
import com.salkcoding.oswl.domain.enums.AiProvider;
import com.salkcoding.oswl.repository.AiSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;

/**
 * Registers the running embedded sidecar as the active LOCAL provider. Kept as a separate
 * bean so the transactional boundary actually applies — a @Transactional method invoked
 * from within the same class (self-invocation) bypasses the proxy and runs without a
 * transaction, so the deactivate/activate saves below would not be atomic.
 */
@Service
@RequiredArgsConstructor
public class EmbeddedAiProviderRegistrar {

    private final AiSettingRepository aiSettingRepository;

    /** Registers the running sidecar as the active LOCAL provider (own transaction). */
    @Transactional
    public void registerAsActiveProvider(String modelName, String baseUrl) {
        AiSetting setting = aiSettingRepository.findByProvider(AiProvider.LOCAL)
                .orElseGet(() -> AiSetting.builder().provider(AiProvider.LOCAL).build());
        setting.update(null, modelName, baseUrl);
        // Marks this LOCAL row as the built-in sidecar so the settings page can show it as the
        // Embedded entry rather than as an external Ollama endpoint.
        setting.markEmbeddedManaged(true);
        aiSettingRepository.findByActiveTrue()
                .filter(s -> s.getProvider() != AiProvider.LOCAL)
                .ifPresent(other -> {
                    other.deactivate();
                    aiSettingRepository.save(other);
                });
        setting.activate();
        aiSettingRepository.save(setting);
        deactivateOtherActiveProviders(setting);
    }

    /** Heals duplicate-active rows left by a concurrent activate race; normally a no-op. */
    private void deactivateOtherActiveProviders(AiSetting keep) {
        for (AiSetting other : aiSettingRepository.findAllByActiveTrueOrderByUpdatedAtDesc()) {
            if (other.isActive() && !Objects.equals(other.getId(), keep.getId())) {
                other.deactivate();
                aiSettingRepository.save(other);
            }
        }
    }
}
