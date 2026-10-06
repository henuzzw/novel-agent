package com.novelagent.writing.api;

import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ChapterContractVersion;
import java.time.Instant;
import java.util.UUID;

/**
 * 合同内容、来源大纲及确认状态。
 *
 * <p>将领域对象转换为接口响应快照，明确保留合同内容、来源大纲及确认状态。转换本身不查询数据库、不改变业务状态，显示内容与持久化来源不能混淆。</p>
 */
public record ChapterContractResponse(UUID id, UUID projectId, UUID sourceOutlineVersionId,
        UUID baseContractVersionId, int chapterNumber,
        int versionNumber, String schemaVersion, ChapterContractStatus status, String generatorType,
        String authorInstruction, ChapterContractContent content, long version, Instant createdAt, Instant updatedAt) {
    /**
     * 将领域版本映射为合同内容、来源大纲及确认状态。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param value 待映射的领域版本，保留其 ID、状态与并发版本。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static ChapterContractResponse from(ChapterContractVersion value) {
        return new ChapterContractResponse(value.getId(), value.getProjectId(), value.getSourceOutlineVersionId(),
                value.getBaseContractVersionId(),
                value.getChapterNumber(), value.getVersionNumber(), value.getSchemaVersion(), value.getStatus(),
                value.getGeneratorType(), value.getAuthorInstruction(), value.getContent(), value.getRowVersion(),
                value.getCreatedAt(), value.getUpdatedAt());
    }
}
