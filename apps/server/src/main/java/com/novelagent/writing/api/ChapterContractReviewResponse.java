package com.novelagent.writing.api;

import com.novelagent.writing.domain.ChapterContractReviewContent;
import com.novelagent.writing.domain.ChapterContractReviewVersion;
import com.novelagent.writing.domain.ReviewStatus;
import java.time.Instant;
import java.util.UUID;

/**
 * 合同审阅内容与源合同行版本。
 *
 * <p>将领域对象转换为接口响应快照，明确保留合同审阅内容与源合同行版本。转换本身不查询数据库、不改变业务状态，显示内容与持久化来源不能混淆。</p>
 */
public record ChapterContractReviewResponse(UUID id, UUID projectId, int chapterNumber,
        UUID sourceContractVersionId, long sourceContractRowVersion, int versionNumber, ReviewStatus status,
        String generatorType, String authorInstruction, ChapterContractReviewContent content,
        long version, Instant createdAt, Instant updatedAt) {
    /**
     * 将领域版本映射为合同审阅内容与源合同行版本。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param value 待映射的领域版本，保留其 ID、状态与并发版本。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static ChapterContractReviewResponse from(ChapterContractReviewVersion value) {
        return new ChapterContractReviewResponse(value.getId(), value.getProjectId(), value.getChapterNumber(),
                value.getSourceContractVersionId(), value.getSourceContractRowVersion(), value.getVersionNumber(),
                value.getStatus(), value.getGeneratorType(), value.getAuthorInstruction(), value.getContent(),
                value.getRowVersion(), value.getCreatedAt(), value.getUpdatedAt());
    }
}
