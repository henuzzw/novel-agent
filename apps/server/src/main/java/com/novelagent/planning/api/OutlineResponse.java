package com.novelagent.planning.api;

import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.OutlineWordBudget;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 大纲内容、生成来源、修改说明与编辑版本。
 *
 * <p>将领域对象转换为接口响应快照，明确保留大纲内容、生成来源、修改说明与编辑版本。转换本身不查询数据库、不改变业务状态，显示内容与持久化来源不能混淆。</p>
 */
public record OutlineResponse(UUID id, UUID projectId, int generationNumber, String schemaVersion,
        OutlineStatus status, String generatorType, String authorInstruction, UUID sourceBibleVersionId,
        UUID baseOutlineVersionId,
        OutlineWordBudget wordBudget, OutlineContent content, List<String> changeSummary,
        long version, Instant createdAt, Instant updatedAt) {
    /**
     * 将领域版本映射为大纲内容、生成来源、修改说明与编辑版本。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param value 待映射的领域版本，保留其 ID、状态与并发版本。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static OutlineResponse from(OutlineVersion value) {
        return from(value, value.getContent());
    }

    /**
     * 将领域版本映射为大纲内容、生成来源、修改说明与编辑版本。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param value 待映射的领域版本，保留其 ID、状态与并发版本。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static OutlineResponse from(OutlineVersion value, OutlineContent content) {
        return new OutlineResponse(value.getId(), value.getProjectId(), value.getGenerationNumber(),
                value.getSchemaVersion(), value.getStatus(), value.getGeneratorType(), value.getAuthorInstruction(),
                value.getSourceBibleVersionId(), value.getBaseOutlineVersionId(), value.getWordBudget(),
                content, value.getChangeSummary(), value.getRowVersion(),
                value.getCreatedAt(), value.getUpdatedAt());
    }
}
