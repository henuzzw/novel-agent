package com.novelagent.project.application;

import com.novelagent.project.domain.CodexModelChoice;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.domain.GlobalModelSettings;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 兼容模型配置。
 *
 * <p>为旧项目接口转接当前用户全局 Codex 配置。校验项目归属和模型强度组合，保留供应商与 DeepSeek 配置，不建立项目级覆盖。</p>
 */
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

    /**
     * 校验项目归属后读取当前用户全局 Codex 模型和强度，项目路径不表示项目级覆盖。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @Transactional(readOnly = true)
    public CodexModelChoice get(UUID projectId) {
        access.requireOwnedProject(projectId);
        var value = settings.get();
        return new CodexModelChoice(value.codexModel(), value.codexEffort());
    }

    /**
     * 校验项目和 Codex 能力组合，只替换全局 Codex 模型强度，保留当前供应商和 DeepSeek 模型。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param choice 作者选择的 Codex 模型与强度组合。
     */
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
