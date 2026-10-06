package com.novelagent.project.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.domain.GlobalModelSettings;
import com.novelagent.project.infrastructure.GlobalModelSettingsRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GlobalModelSettingsServiceTest {
    private final UUID user = UUID.randomUUID();
    private final GlobalModelSettingsRepository repository = mock(GlobalModelSettingsRepository.class);
    private final GlobalModelSettingsService service = new GlobalModelSettingsService(repository,
            new CurrentActorProvider(user), "gpt-6.1-sol", "high", "deepseek-v4-flash");

    @Test void defaultsNormalizeLegacyFlashNameWithoutWriting() {
        when(repository.find(user)).thenReturn(Optional.empty());
        assertThat(service.get()).isEqualTo(new GlobalModelSettings(ModelProvider.LOCAL_CODEX,
                "gpt-6.1-sol", "high", "deepseek-flash", 0));
    }

    @Test void settingsAreUserScopedAndUpdateReturnsTheNextVersion() {
        var value = new GlobalModelSettings(ModelProvider.DEEPSEEK, "gpt-6-sol", "low", "deepseek-v4-pro", 3);
        when(repository.find(user)).thenReturn(Optional.of(value));
        when(repository.save(user, value)).thenReturn(true);
        assertThat(service.get()).isEqualTo(value);
        assertThat(service.update(value).version()).isEqualTo(4);
        verify(repository).save(user, value);
    }

    @Test void staleSaveDoesNotSilentlyOverwriteSettings() {
        var value = new GlobalModelSettings(ModelProvider.DEEPSEEK, "gpt-6-sol", "low", "deepseek-v4-pro", 0);
        when(repository.find(user)).thenReturn(Optional.of(new GlobalModelSettings(value.provider(),
                value.codexModel(), value.codexEffort(), value.deepSeekModel(), 2)));
        assertThatThrownBy(() -> service.update(value)).isInstanceOf(ResourceVersionConflictException.class);
    }

    @Test void invalidSettingsAreRejected() {
        assertThatThrownBy(() -> new GlobalModelSettings(null, "gpt-6-sol", "high", "deepseek-flash", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GlobalModelSettings(ModelProvider.DEEPSEEK, "gpt-6-sol", "high", "v4pro", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new GlobalModelSettings(ModelProvider.LOCAL_CODEX, "gpt-6-sol", "invalid", "deepseek-flash", 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
