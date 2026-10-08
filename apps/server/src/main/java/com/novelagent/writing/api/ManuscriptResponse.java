package com.novelagent.writing.api;

import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ManuscriptVersion;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 正文内容、基准稿来源及修改说明。
 *
 * <p>将领域对象转换为接口响应快照，明确保留正文内容、基准稿来源及修改说明。转换本身不查询数据库、不改变业务状态，显示内容与持久化来源不能混淆。</p>
 */
public record ManuscriptResponse(UUID id, UUID projectId, UUID sourceContractVersionId,
        UUID baseManuscriptVersionId, UUID sourceReviewVersionId, int chapterNumber,
        int versionNumber, String schemaVersion, ManuscriptStatus status, String generatorType,
        String authorInstruction, ManuscriptContent content, List<String> changeSummary,
        long version, Instant createdAt, Instant updatedAt, com.novelagent.writing.domain.ManuscriptBasis writingBasis) {
    public ManuscriptResponse(UUID id, UUID projectId, UUID sourceContractVersionId,
            UUID baseManuscriptVersionId, UUID sourceReviewVersionId, int chapterNumber,
            int versionNumber, String schemaVersion, ManuscriptStatus status, String generatorType,
            String authorInstruction, ManuscriptContent content, List<String> changeSummary,
            long version, Instant createdAt, Instant updatedAt) {
        this(id, projectId, sourceContractVersionId, baseManuscriptVersionId, sourceReviewVersionId, chapterNumber,
                versionNumber, schemaVersion, status, generatorType, authorInstruction, content, changeSummary,
                version, createdAt, updatedAt, null);
    }
    /**
     * 将领域版本映射为正文内容、基准稿来源及修改说明。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param value 待映射的领域版本，保留其 ID、状态与并发版本。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static ManuscriptResponse from(ManuscriptVersion value) {
        return from(value, value.getContent());
    }

    /**
     * 将领域版本映射为正文内容、基准稿来源及修改说明。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param value 待映射的领域版本，保留其 ID、状态与并发版本。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static ManuscriptResponse from(ManuscriptVersion value, ManuscriptContent content) {
        return new ManuscriptResponse(value.getId(), value.getProjectId(), value.getSourceContractVersionId(),
                value.getBaseManuscriptVersionId(), value.getSourceReviewVersionId(),
                value.getChapterNumber(), value.getVersionNumber(), value.getSchemaVersion(), value.getStatus(),
                value.getGeneratorType(), value.getAuthorInstruction(), content, value.getChangeSummary(), value.getRowVersion(),
                value.getCreatedAt(), value.getUpdatedAt(), value.getWritingBasis());
    }
}
