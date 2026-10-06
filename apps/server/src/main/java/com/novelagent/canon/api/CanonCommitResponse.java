package com.novelagent.canon.api;

import com.novelagent.canon.domain.CanonCommit;
import com.novelagent.writing.domain.FactProposal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 正史提交标识、章节及正史版本。
 *
 * <p>将领域对象转换为接口响应快照，明确保留正史提交标识、章节及正史版本。转换本身不查询数据库、不改变业务状态，显示内容与持久化来源不能混淆。</p>
 */
public record CanonCommitResponse(UUID id, UUID projectId, int chapterNumber, UUID manuscriptVersionId,
        UUID reviewVersionId, long canonVersion, List<FactProposal> acceptedFacts, Instant createdAt) {

    /**
     * 将领域版本映射为正史提交标识、章节及正史版本。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param commit 已保存的正史提交，包含投影来源及版本水位。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static CanonCommitResponse from(CanonCommit commit) {
        return new CanonCommitResponse(
                commit.getId(),
                commit.getProjectId(),
                commit.getChapterNumber(),
                commit.getManuscriptVersionId(),
                commit.getReviewVersionId(),
                commit.getCanonVersion(),
                commit.getAcceptedFacts(),
                commit.getCreatedAt());
    }
}
