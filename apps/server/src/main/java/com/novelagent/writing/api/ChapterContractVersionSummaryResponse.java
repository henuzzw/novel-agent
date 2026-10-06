package com.novelagent.writing.api;

import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ChapterContractVersion;
import java.time.Instant;
import java.util.UUID;

/**
 * 合同历史摘要及渲染后的章节标题。
 *
 * <p>将领域对象转换为接口响应快照，明确保留合同历史摘要及渲染后的章节标题。转换本身不查询数据库、不改变业务状态，显示内容与持久化来源不能混淆。</p>
 */
public record ChapterContractVersionSummaryResponse(UUID id, int versionNumber, ChapterContractStatus status,
        UUID sourceOutlineVersionId, UUID baseContractVersionId, String chapterTitle, Instant createdAt) {
    /**
     * 将领域版本映射为合同历史摘要及渲染后的章节标题。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param value 待映射的领域版本，保留其 ID、状态与并发版本。
     * @param title 作者指定称谓或当前记录标题，含义见业务对象。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static ChapterContractVersionSummaryResponse from(ChapterContractVersion value, String title) {
        return new ChapterContractVersionSummaryResponse(value.getId(), value.getVersionNumber(), value.getStatus(),
                value.getSourceOutlineVersionId(), value.getBaseContractVersionId(), title, value.getCreatedAt());
    }
}
