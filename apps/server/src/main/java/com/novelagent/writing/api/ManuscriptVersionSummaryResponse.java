package com.novelagent.writing.api;

import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ManuscriptVersion;
import java.time.Instant;
import java.util.UUID;

/**
 * 正文版本摘要及渲染后的标题。
 *
 * <p>将领域对象转换为接口响应快照，明确保留正文版本摘要及渲染后的标题。转换本身不查询数据库、不改变业务状态，显示内容与持久化来源不能混淆。</p>
 */
public record ManuscriptVersionSummaryResponse(UUID id, int versionNumber, ManuscriptStatus status,
        UUID sourceContractVersionId, UUID baseManuscriptVersionId, String title,
        int bodyLength, Instant createdAt) {
    /**
     * 将领域版本映射为正文版本摘要及渲染后的标题。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param value 待映射的领域版本，保留其 ID、状态与并发版本。
     * @param title 作者指定称谓或当前记录标题，含义见业务对象。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static ManuscriptVersionSummaryResponse from(ManuscriptVersion value, String title) {
        return new ManuscriptVersionSummaryResponse(value.getId(), value.getVersionNumber(), value.getStatus(),
                value.getSourceContractVersionId(), value.getBaseManuscriptVersionId(), title,
                value.getContent().body().length(), value.getCreatedAt());
    }
}
