package com.novelagent.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.CreationPreparationSchema;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 创作准备执行。
 *
 * <p>串行执行人物世界设计、剧情协同设计和一致性复核，或仅执行 REVIEW。模型在短事务之外调用，尝试与来源在保存时重检；all 仍遵循相同串行门禁。</p>
 */
@Service
public class CreationPreparationRunner {
    private final CreationPreparationStore store;
    private final StructuredModelGateway models;
    private final CreationPreparationSchema schemas;
    private final CharacterDesignService designer;
    public CreationPreparationRunner(CreationPreparationStore store, StructuredModelGateway models, CreationPreparationSchema schemas, CharacterDesignService designer) {
        this.store = store; this.models = models; this.schemas = schemas; this.designer = designer;
    }
    /**
     * 执行当前任务的下一个待处理阶段或分段；认领、来源复核及结果保存由专责存储服务控制。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param version 本次操作要求匹配的业务行版本。
     */
    public CreationPreparationStore.View next(UUID projectId, UUID id, long version) {
        var claim = store.claim(projectId, id, version);
        try {
            String stage = switch (claim.nextStep()) { case 0 -> "WORLD"; case 1 -> "PLOT"; case 2 -> "REVIEW"; default -> throw new IllegalArgumentException("任务阶段无效"); };
            String prompt = switch (claim.nextStep()) { case 0 -> ""; case 1 -> CreationPreparationPrompt.plot(); default -> CreationPreparationPrompt.review(); };
            JsonNode schema = switch (claim.nextStep()) { case 0 -> schemas.world(); case 1 -> schemas.plot(); default -> schemas.review(); };
            String raw = claim.nextStep() == 0
                    ? designer.request(projectId, claim.provider(), store.modelInput(claim), schema, "character_world_design", 12000)
                    : models.request(projectId, "CREATION_PREPARATION_" + stage, claim.provider(), prompt,
                    store.modelInput(claim).toString(), schema, "creation_preparation_" + stage.toLowerCase(java.util.Locale.ROOT),
                    claim.nextStep() == 2 ? 8000 : 12000, CodexSessionPolicy.NEW_THREAD);
            return store.finish(claim, store.parse(raw));
        } catch (RuntimeException failure) {
            store.fail(claim, failure);
            return store.get(projectId, id);
        }
    }
    /**
     * 在同一准备任务上串行推进尚未完成的阶段，遇到等待确认或失败即停止；不跳过作者门禁。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param version 本次操作要求匹配的业务行版本。
     */
    public CreationPreparationStore.View all(UUID projectId, UUID id, long version) {
        var result = next(projectId, id, version);
        for (int step = 0; step < 2 && result.task().status().equals("READY"); step++) {
            result = next(projectId, id, result.task().version());
        }
        return result;
    }
}
