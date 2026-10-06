package com.novelagent.planning.api;

import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import java.time.Instant;
import java.util.UUID;

/**
 * 圣经历史版本摘要及当前指针标记。
 *
 * <p>将领域对象转换为接口响应快照，明确保留圣经历史版本摘要及当前指针标记。转换本身不查询数据库、不改变业务状态，显示内容与持久化来源不能混淆。</p>
 */
public record StoryBibleVersionSummaryResponse(UUID id, int generationNumber, StoryBibleStatus status,
        String logline, UUID baseBibleVersionId, Instant createdAt) {
    /**
     * 将领域版本映射为圣经历史版本摘要及当前指针标记。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param version 本次操作要求匹配的业务行版本。
     * @param renderedLogline 按当前人物命名渲染后的故事概述。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static StoryBibleVersionSummaryResponse from(StoryBibleVersion version, String renderedLogline) {
        return new StoryBibleVersionSummaryResponse(version.getId(), version.getGenerationNumber(),
                version.getStatus(), renderedLogline, version.getBaseBibleVersionId(), version.getCreatedAt());
    }
}
