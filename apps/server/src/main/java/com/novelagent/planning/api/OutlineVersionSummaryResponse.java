package com.novelagent.planning.api;

import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.OutlineVersion;
import java.time.Instant;
import java.util.UUID;

/**
 * 大纲版本历史摘要及当前指针标记。
 *
 * <p>将领域对象转换为接口响应快照，明确保留大纲版本历史摘要及当前指针标记。转换本身不查询数据库、不改变业务状态，显示内容与持久化来源不能混淆。</p>
 */
public record OutlineVersionSummaryResponse(UUID id, int generationNumber, OutlineStatus status,
        String title, int chapterCount, UUID sourceBibleVersionId, UUID baseOutlineVersionId,
        Instant createdAt) {
    /**
     * 将领域版本映射为大纲版本历史摘要及当前指针标记。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param version 本次操作要求匹配的业务行版本。
     * @param renderedTitle 按当前人物命名渲染后的标题，不改变底层占位符。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static OutlineVersionSummaryResponse from(OutlineVersion version, String renderedTitle) {
        return new OutlineVersionSummaryResponse(version.getId(), version.getGenerationNumber(),
                version.getStatus(), renderedTitle, version.getContent().chapterCount(),
                version.getSourceBibleVersionId(), version.getBaseOutlineVersionId(),
                version.getCreatedAt());
    }
}
