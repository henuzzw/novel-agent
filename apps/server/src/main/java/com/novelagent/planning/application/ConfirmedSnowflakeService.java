package com.novelagent.planning.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.ingest.application.ImportedPlanningSources;
import com.novelagent.planning.api.StoryBibleResponse;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.SnowflakePlanStore;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.CreativeBoundaryGuide;
import com.novelagent.project.application.CreativeStrategyGuide;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.CreativeBoundaries;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 雪花规划确认用例。锁定项目并复核完整步骤及冻结来源，固定确切运行的资料快照并同步规划资料，不调用模型。
 */
@Service
public class ConfirmedSnowflakeService {
    private final ProjectAccessService access;
    private final SnowflakePlanStore plans;
    private final StoryBibleVersionRepository snapshots;
    private final NovelProjectRepository projects;
    private final PlanningMaterialSyncService materials;
    private final CharacterNameService names;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final ImportedPlanningSources sources;

    public ConfirmedSnowflakeService(
            ProjectAccessService access,
            SnowflakePlanStore plans,
            StoryBibleVersionRepository snapshots,
            NovelProjectRepository projects,
            PlanningMaterialSyncService materials,
            CharacterNameService names,
            ObjectMapper mapper,
            JdbcTemplate jdbc,
            ImportedPlanningSources sources) {
        this.access = access;
        this.plans = plans;
        this.snapshots = snapshots;
        this.projects = projects;
        this.materials = materials;
        this.names = names;
        this.mapper = mapper;
        this.jdbc = jdbc;
        this.sources = sources;
    }

    /**
     * 确认最新完整且来源未变化的雪花运行，幂等创建已发布资料快照；同一当前版本重复确认不覆盖作者后续档案修改。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param planId 待确认或恢复的雪花运行 ID
     */
    @Transactional
    public StoryBibleResponse confirm(UUID projectId, UUID planId) {
        jdbc.queryForObject(
                "SELECT id FROM novel_project WHERE id = ? FOR UPDATE", UUID.class, projectId);
        var project = access.requireOwnedProject(projectId);
        var plan = plans.get(projectId, planId);
        if (!"SUCCEEDED".equals(plan.status())
                || plans.latest(projectId).filter(value -> value.id().equals(planId)).isEmpty()) {
            throw new IllegalArgumentException("只能确认当前最新且完整成功的雪花规划");
        }
        if (!plan.invalidSteps().isEmpty())
            throw new IllegalArgumentException("雪花规划包含资料读取失败说明，不能确认，请重新生成");
        var input = plans.input(projectId, planId);
        String boundaries = CreativeBoundaryGuide.render(CreativeBoundaries.from(project));
        String strategy = CreativeStrategyGuide.render(CreativeStrategyPolicy.from(project));
        if (!boundaries.equals(input.path("creativeBoundarySnapshot").asText())
                || !strategy.equals(input.path("creativeStrategySnapshot").asText())) {
            throw new IllegalArgumentException("创作边界或策略已改变，请重新生成雪花规划");
        }
        if (plan.sourceImportId() != null) {
            sources.requireUnchanged(
                    projectId, plan.sourceImportId(), input, "原文或确认解析已改变，请重新生成雪花规划");
        }
        for (var step : SnowflakeStep.values()) {
            if (step == SnowflakeStep.SCENE_EXPANSION
                    && !input.path("expandScenes").asBoolean(false)) continue;
            if (plan.steps().getOrDefault(step.name(), "").isBlank())
                throw new IllegalArgumentException("雪花步骤未完整保存");
        }
        var existing =
                snapshots.findByProjectIdAndSourceSnowflakeIdAndSourceDirectionSetIdIsNull(
                        projectId, planId);
        StoryBibleVersion snapshot =
                existing.orElseGet(
                        () -> {
                            StoryBibleContent content =
                                    mapper.convertValue(
                                            plans.materials(projectId, planId),
                                            StoryBibleContent.class);
                            int generation =
                                    snapshots
                                            .findFirstByProjectIdOrderByGenerationNumberDesc(
                                                    projectId)
                                            .map(value -> value.getGenerationNumber() + 1)
                                            .orElse(1);
                            var value =
                                    StoryBibleVersion.create(
                                            UUID.randomUUID(),
                                            projectId,
                                            generation,
                                            "SNOWFLAKE_CONFIRMED",
                                            input.path("authorInstruction").asText(""),
                                            null,
                                            null,
                                            content.withDevelopmentNotes(plan.context()),
                                            List.of());
                            value.linkSnowflake(planId);
                            if (plan.sourceImportId() != null)
                                value.linkImport(plan.sourceImportId());
                            value.publish();
                            return snapshots.saveAndFlush(value);
                        });
        if (!snapshot.getId().equals(project.getCurrentBibleVersionId())) {
            project.publishStoryBible(snapshot.getId());
            projects.saveAndFlush(project);
            materials.syncBible(snapshot);
        }
        return StoryBibleResponse.from(
                snapshot, names.render(projectId, snapshot.getContent(), StoryBibleContent.class));
    }

    /**
     * 按项目和确切版本 ID 读取规划资料，并按当前人物姓名渲染展示内容，不修改已保存快照。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param versionId 确切规划资料版本 ID
     */
    @Transactional(readOnly = true)
    public StoryBibleResponse version(UUID projectId, UUID versionId) {
        access.requireOwnedProject(projectId);
        var snapshot =
                snapshots
                        .findByIdAndProjectId(versionId, projectId)
                        .orElseThrow(() -> new StoryBibleVersionNotFoundException(versionId));
        return StoryBibleResponse.from(
                snapshot, names.render(projectId, snapshot.getContent(), StoryBibleContent.class));
    }

    /**
     * 读取项目当前已发布且关联雪花运行的规划快照；缺少当前指针或有效快照时返回空。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     */
    @Transactional(readOnly = true)
    public Optional<StoryBibleResponse> current(UUID projectId) {
        var project = access.requireOwnedProject(projectId);
        if (project.getCurrentBibleVersionId() == null) return Optional.empty();
        return snapshots
                .findByIdAndProjectId(project.getCurrentBibleVersionId(), projectId)
                .filter(value -> value.getSourceSnowflakeId() != null)
                .filter(value -> value.getStatus() == StoryBibleStatus.PUBLISHED)
                .map(
                        value ->
                                StoryBibleResponse.from(
                                        value,
                                        names.render(
                                                projectId,
                                                value.getContent(),
                                                StoryBibleContent.class)));
    }
}
