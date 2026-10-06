package com.novelagent.project.application;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.domain.GlobalModelSettings;
import com.novelagent.project.infrastructure.GlobalModelSettingsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class GlobalModelSettingsService {
    private final GlobalModelSettingsRepository repository;
    private final CurrentActorProvider actors;
    private final GlobalModelSettings defaults;

    public GlobalModelSettingsService(GlobalModelSettingsRepository repository, CurrentActorProvider actors,
            @Value("${app.ai.codex.model}") String codexModel,
            @Value("${app.ai.codex.effort}") String codexEffort,
            @Value("${app.ai.deepseek.model}") String deepSeekModel) {
        this.repository = repository;
        this.actors = actors;
        String normalized = "deepseek-v4-flash".equals(deepSeekModel) ? "deepseek-flash" : deepSeekModel;
        this.defaults = new GlobalModelSettings(ModelProvider.LOCAL_CODEX, codexModel, codexEffort, normalized, 0);
    }

    @Transactional(readOnly = true)
    public GlobalModelSettings get() {
        return repository.find(actors.currentUserId()).orElse(defaults);
    }

    @Transactional
    public GlobalModelSettings update(GlobalModelSettings value) {
        if (!repository.save(actors.currentUserId(), value)) {
            long actual = repository.find(actors.currentUserId()).map(GlobalModelSettings::version).orElse(0L);
            throw new ResourceVersionConflictException(value.version(), actual);
        }
        return new GlobalModelSettings(value.provider(), value.codexModel(), value.codexEffort(),
                value.deepSeekModel(), value.version() + 1);
    }
}
