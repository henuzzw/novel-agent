package com.novelagent.project.application;

import com.novelagent.project.domain.CodexModelChoice;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.domain.GlobalModelSettings;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectCodexModelService {
    private final ProjectAccessService access;
    private final GlobalModelSettingsService settings;
    private final GlobalModelCatalogService catalog;

    public ProjectCodexModelService(ProjectAccessService access, GlobalModelSettingsService settings,
            GlobalModelCatalogService catalog) {
        this.access = access;
        this.settings = settings;
        this.catalog = catalog;
    }

    @Transactional(readOnly = true)
    public CodexModelChoice get(UUID projectId) {
        access.requireOwnedProject(projectId);
        var value = settings.get();
        return new CodexModelChoice(value.codexModel(), value.codexEffort());
    }

    public CodexModelChoice update(UUID projectId, CodexModelChoice choice) {
        access.requireOwnedProject(projectId);
        var current = settings.get();
        var value = new GlobalModelSettings(current.provider(), choice.model(), choice.effort(),
                current.deepSeekModel(), current.version());
        catalog.validate(new GlobalModelSettings(ModelProvider.LOCAL_CODEX,
                value.codexModel(), value.codexEffort(), value.deepSeekModel(), value.version()));
        settings.update(value);
        return choice;
    }
}
