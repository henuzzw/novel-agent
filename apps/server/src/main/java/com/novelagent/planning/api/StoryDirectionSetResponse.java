package com.novelagent.planning.api;

import com.novelagent.planning.domain.StoryDirectionCandidate;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.StoryDirectionSet;
import com.novelagent.planning.domain.StoryDirectionStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 候选方向集合、作者选择及行版本。
 *
 * <p>将领域对象转换为接口响应快照，明确保留候选方向集合、作者选择及行版本。转换本身不查询数据库、不改变业务状态，显示内容与持久化来源不能混淆。</p>
 */
public record StoryDirectionSetResponse(
        UUID id,
        UUID projectId,
        int generationNumber,
        String schemaVersion,
        StoryDirectionStatus status,
        String generatorType,
        String authorInstruction,
        long sourceIntentVersion,
        OutlineWordBudget wordBudget,
        List<StoryDirectionCandidate> directions,
        List<String> questionsForAuthor,
        List<String> changeSummary,
        UUID selectedCandidateId,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    /**
     * 将领域版本映射为候选方向集合、作者选择及行版本。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param set 候选故事方向集合及选择状态。
     * @param wordBudget 规划使用的目标字数预算。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static StoryDirectionSetResponse from(StoryDirectionSet set, OutlineWordBudget wordBudget) {
        return new StoryDirectionSetResponse(
                set.getId(),
                set.getProjectId(),
                set.getGenerationNumber(),
                set.getSchemaVersion(),
                set.getStatus(),
                set.getGeneratorType(),
                set.getAuthorInstruction(),
                set.getInputSnapshot().sourceVersion(),
                wordBudget,
                set.getDirections(),
                set.getQuestionsForAuthor(),
                set.getChangeSummary(),
                set.getSelectedCandidateId(),
                set.getRowVersion(),
                set.getCreatedAt(),
                set.getUpdatedAt());
    }
}
