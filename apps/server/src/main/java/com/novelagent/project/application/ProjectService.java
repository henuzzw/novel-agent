package com.novelagent.project.application;

import com.novelagent.project.api.CreateProjectRequest;
import com.novelagent.project.api.CreativeIntentRequest;
import com.novelagent.project.api.ProjectResponse;
import com.novelagent.project.domain.CreativeIntent;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.CreativeIntentRepository;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 小说项目。
 *
 * <p>创建项目和可选创作意图，维护当前用户项目视图与意图编辑。IDEA 入口要求意图；更新已有意图必须匹配其行版本，不能用生成序号代替。</p>
 */
@Service
public class ProjectService {

    private final NovelProjectRepository projectRepository;
    private final CreativeIntentRepository creativeIntentRepository;
    private final CurrentActorProvider actorProvider;
    private final ProjectAccessService access;

    public ProjectService(
            NovelProjectRepository projectRepository,
            CreativeIntentRepository creativeIntentRepository,
            CurrentActorProvider actorProvider,
            ProjectAccessService access) {
        this.projectRepository = projectRepository;
        this.creativeIntentRepository = creativeIntentRepository;
        this.actorProvider = actorProvider;
        this.access = access;
    }

    /**
     * 校验 IDEA 入口的创作意图，在事务中保存归属当前用户的项目、初始策略及可选意图，不调用模型。
     *
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @Transactional
    public ProjectResponse create(CreateProjectRequest request) {
        validateIdeaRequest(request.entryMode(), request.creativeIntent());

        UUID projectId = UUID.randomUUID();
        NovelProject project = NovelProject.create(
                projectId,
                actorProvider.currentUserId(),
                request.name() == null || request.name().isBlank() ? "待生成书名" : request.name(),
                request.entryMode());
        if (request.name() == null || request.name().isBlank()) {
            project.setSetting("automaticTitle", java.util.Map.of("pending", true));
        }
        CreativeStrategyPolicy.of(request.creativeStrategy()).applyTo(project);
        projectRepository.saveAndFlush(project);

        CreativeIntent intent = null;
        if (request.creativeIntent() != null) {
            intent = new CreativeIntent(projectId);
            apply(intent, request.creativeIntent());
            creativeIntentRepository.saveAndFlush(intent);
        }

        return ProjectResponse.from(project, intent);
    }

    /**
     * 按当前用户查询项目并装配其可选创作意图，排序沿用项目更新时间，不返回其他用户项目。
     *
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<ProjectResponse> list() {
        UUID ownerId = actorProvider.currentUserId();
        return projectRepository.findAllByOwnerIdOrderByUpdatedAtDesc(ownerId).stream()
                .map(project -> ProjectResponse.from(
                        project,
                        creativeIntentRepository.findById(project.getId()).orElse(null)))
                .toList();
    }

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @Transactional(readOnly = true)
    public ProjectResponse get(UUID projectId) {
        NovelProject project = access.requireOwnedProject(projectId);
        CreativeIntent intent = creativeIntentRepository.findById(projectId).orElse(null);
        return ProjectResponse.from(project, intent);
    }

    /**
     * 校验项目归属，按已有意图 rowVersion 保存作者要求；无意图时创建，返回项目视图中的最新意图版本。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @Transactional
    public ProjectResponse updateCreativeIntent(UUID projectId, long expectedVersion, CreativeIntentRequest request) {
        NovelProject project = access.requireOwnedProject(projectId);
        CreativeIntent intent = creativeIntentRepository.findById(projectId)
                .orElseGet(() -> new CreativeIntent(projectId));

        if (creativeIntentRepository.existsById(projectId) && intent.getRowVersion() != expectedVersion) {
            throw new ResourceVersionConflictException(expectedVersion, intent.getRowVersion());
        }

        apply(intent, request);
        creativeIntentRepository.saveAndFlush(intent);
        return ProjectResponse.from(project, intent);
    }

    private static void validateIdeaRequest(EntryMode entryMode, CreativeIntentRequest intent) {
        if (entryMode == EntryMode.IDEA && intent == null) {
            throw new IllegalArgumentException("Creative intent is required for IDEA entry mode");
        }
    }

    private static void apply(CreativeIntent intent, CreativeIntentRequest request) {
        intent.update(
                request.premise(),
                request.genres(),
                request.targetAudience(),
                request.protagonistBrief(),
                request.centralConflict(),
                request.tones(),
                request.targetWords(),
                request.endingPreference(),
                request.mustHave(),
                request.avoid(),
                request.stylePreferences());
    }
}
