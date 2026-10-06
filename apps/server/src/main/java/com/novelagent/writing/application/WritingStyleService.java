package com.novelagent.writing.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.writing.domain.WritingStyleProfile;
import com.novelagent.writing.infrastructure.WritingStylePresetCatalog;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 项目写作风格。
 *
 * <p>管理项目风格快照及数据库预设解析，按项目行版本显式应用或清除。生成和检查共用展开指南，表达规则不能改变事实、人物身份或视角。</p>
 */
@Service
public class WritingStyleService {
    private final NovelProjectRepository projects;
    private final CurrentActorProvider actors;
    private final ObjectMapper mapper;
    private final WritingStylePresetCatalog catalog;

    public WritingStyleService(NovelProjectRepository projects, CurrentActorProvider actors, ObjectMapper mapper,
            WritingStylePresetCatalog catalog) {
        this.projects = projects;
        this.actors = actors;
        this.mapper = mapper;
        this.catalog = catalog;
    }

    public record StyleState(WritingStyleProfile profile, long version) { }

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @Transactional(readOnly = true)
    public StyleState get(UUID projectId) {
        NovelProject project = requireOwnedProject(projectId);
        Object value = project.getSetting("writingStyle");
        return new StyleState(value == null ? null : catalog.resolve(mapper.convertValue(value, WritingStyleProfile.class)), project.getRowVersion());
    }

    /**
     * 读取数据库中启用的完整版本化风格目录；不按名称拼装或使用代码中的默认预设回退。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<WritingStyleProfile> presets(UUID projectId) {
        requireOwnedProject(projectId);
        return catalog.active();
    }

    /**
     * 保存作者明确采用的风格快照或清除风格，按项目行版本保护；推荐和试写不会替代此应用动作。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param profile 候选或已选完整风格档案，包含版本与技法快照。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     */
    @Transactional
    public StyleState apply(UUID projectId, WritingStyleProfile profile, long expectedVersion) {
        NovelProject project = requireOwnedProject(projectId);
        if (project.getRowVersion() != expectedVersion) throw new ResourceVersionConflictException(expectedVersion, project.getRowVersion());
        profile = catalog.resolve(profile);
        Map<String, Object> value = profile == null ? null : mapper.convertValue(profile, new TypeReference<Map<String, Object>>() { });
        project.setSetting("writingStyle", value);
        projects.saveAndFlush(project);
        return new StyleState(profile, project.getRowVersion());
    }

    /**
     * 展开项目完整风格快照的执行指南；未配置时沿用圣经风格，任何表达技巧都不能覆盖人物、事实或章节视角。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @Transactional(readOnly = true)
    public String promptContext(UUID projectId) {
        WritingStyleProfile profile = get(projectId).profile();
        return profile == null ? "未指定；沿用故事圣经中的叙事风格。"
                : "风格只指导表达；章节合同的视角、人物身份、事实与硬约束优先。\n" + WritingStyleGuide.render(profile);
    }

    /**
     * 按明确基础预设版本或完整旧档案匹配补齐技法，保持作者已保存字段；同名不足以证明继承关系。
     *
     * @param profile 候选或已选完整风格档案，包含版本与技法快照。
     */
    public WritingStyleProfile resolveProfile(WritingStyleProfile profile) {
        return catalog.resolve(profile);
    }

    private NovelProject requireOwnedProject(UUID projectId) {
        return projects.findById(projectId).filter(project -> project.getOwnerId().equals(actors.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }
}
