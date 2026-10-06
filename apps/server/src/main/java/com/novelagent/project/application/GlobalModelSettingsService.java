package com.novelagent.project.application;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.domain.GlobalModelSettings;
import com.novelagent.project.infrastructure.GlobalModelSettingsRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 全局模型配置。
 *
 * <p>按当前用户保存默认供应商、Codex 模型强度与 DeepSeek 模型。数据库无记录时使用配置默认值，写入带版本条件，避免多个项目页面互相覆盖。</p>
 */
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

    /**
     * 查询当前用户模型设置，无持久化记录时返回配置默认值，不为读取自动写数据库。
     */
    @Transactional(readOnly = true)
    public GlobalModelSettings get() {
        return repository.find(actors.currentUserId()).orElse(defaults);
    }

    /**
     * 按设置行版本插入或更新当前用户配置，未实际写入则报告版本冲突；返回递增后的配置版本。
     *
     * @param value 当前业务对象或作者编辑值，具体类型由方法签名确定。
     */
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
