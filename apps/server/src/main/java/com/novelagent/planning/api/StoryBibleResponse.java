package com.novelagent.planning.api;

import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 圣经内容、生成来源与编辑版本。
 *
 * <p>将领域对象转换为接口响应快照，明确保留圣经内容、生成来源与编辑版本。转换本身不查询数据库、不改变业务状态，显示内容与持久化来源不能混淆。</p>
 */
public record StoryBibleResponse(UUID id, UUID projectId, int generationNumber, String schemaVersion,
        StoryBibleStatus status, String generatorType, String authorInstruction,
        UUID sourceDirectionSetId, UUID sourceCandidateId, UUID sourceImportId, UUID baseBibleVersionId,
        StoryBibleContent content,
        List<String> changeSummary, long version, Instant createdAt, Instant updatedAt) {
    /**
     * 将领域版本映射为圣经内容、生成来源与编辑版本。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param value 待映射的领域版本，保留其 ID、状态与并发版本。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static StoryBibleResponse from(StoryBibleVersion value) {
        return from(value, value.getContent());
    }

    /**
     * 将领域版本映射为圣经内容、生成来源与编辑版本。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param value 待映射的领域版本，保留其 ID、状态与并发版本。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static StoryBibleResponse from(StoryBibleVersion value, StoryBibleContent content) {
        return new StoryBibleResponse(value.getId(), value.getProjectId(), value.getGenerationNumber(),
                value.getSchemaVersion(), value.getStatus(), value.getGeneratorType(), value.getAuthorInstruction(),
                value.getSourceDirectionSetId(), value.getSourceCandidateId(), value.getSourceImportId(),
                value.getBaseBibleVersionId(), content,
                value.getChangeSummary(), value.getRowVersion(), value.getCreatedAt(), value.getUpdatedAt());
    }
}
