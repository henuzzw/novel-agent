package com.novelagent.project.application;

import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 创作策略。
 *
 * <p>校验项目归属并按项目行版本更新策略，保持风格配置不变。Prompt 指南由当前策略展开，切换策略本身不修改任何规划或正文。</p>
 */
@Service
public class CreativeStrategyService {
    private final ProjectAccessService access;
    private final NovelProjectRepository projects;
    private final EntityManager entities;

    public CreativeStrategyService(ProjectAccessService access, NovelProjectRepository projects, EntityManager entities) {
        this.access = access;
        this.projects = projects;
        this.entities = entities;
    }

    public record State(CreativeStrategy strategy, int policyVersion, long version) {}

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @Transactional(readOnly = true)
    public State get(UUID projectId) {
        return state(access.requireOwnedProject(projectId));
    }

    /**
     * 保存作者提交的编辑内容，并遵循当前业务状态及预期版本约束；不隐式触发模型重新生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param strategy 项目选定的创作策略，不替代事实与知识边界。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     */
    @Transactional
    public State update(UUID projectId, CreativeStrategy strategy, long expectedVersion) {
        NovelProject project = access.requireOwnedProject(projectId);
        entities.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        if (project.getRowVersion() != expectedVersion) {
            throw new ResourceVersionConflictException(expectedVersion, project.getRowVersion());
        }
        if (strategy == null) {
            throw new IllegalArgumentException("请选择创作策略");
        }
        CreativeStrategyPolicy.of(strategy).applyTo(project);
        projects.saveAndFlush(project);
        return state(project);
    }

    /**
     * 从项目已保存策略展开结构指南，STANDARD 不套强开篇指标；指南只控制叙事设计，不改变风格或已发生事实。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @Transactional(readOnly = true)
    public String promptContext(UUID projectId) {
        return CreativeStrategyGuide.render(CreativeStrategyPolicy.from(access.requireOwnedProject(projectId)));
    }

    private static State state(NovelProject project) {
        var policy = CreativeStrategyPolicy.from(project);
        return new State(policy.strategy(), policy.policyVersion(), project.getRowVersion());
    }
}
