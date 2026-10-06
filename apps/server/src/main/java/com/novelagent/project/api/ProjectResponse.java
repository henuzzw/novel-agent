package com.novelagent.project.api;

import com.novelagent.project.domain.CreativeIntent;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.domain.ProjectStatus;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 项目指针、创作策略与可选创作意图。
 *
 * <p>将领域对象转换为接口响应快照，明确保留项目指针、创作策略与可选创作意图。转换本身不查询数据库、不改变业务状态，显示内容与持久化来源不能混淆。</p>
 */
public record ProjectResponse(
        UUID id,
        String name,
        EntryMode entryMode,
        ProjectStatus status,
        long currentCanonVersion,
        long version,
        Instant createdAt,
        Instant updatedAt,
        CreativeIntentResponse creativeIntent,
        CreativeStrategy creativeStrategy) {

    /**
     * 将领域版本映射为项目指针、创作策略与可选创作意图。仅转换字段，不查询数据库、不修改状态；传入已渲染内容的重载只影响返回展示，不重写源版本。
     *
     * @param project 已校验归属的小说项目及其当前指针。
     * @param intent 作者保存的创作意图与不可丢失的要求。
     * @return 与领域对象状态一致的接口响应，不改变源记录。
     */
    public static ProjectResponse from(NovelProject project, CreativeIntent intent) {
        return new ProjectResponse(
                project.getId(),
                project.getName(),
                project.getEntryMode(),
                project.getStatus(),
                project.getCurrentCanonVersion(),
                project.getRowVersion(),
                project.getCreatedAt(),
                project.getUpdatedAt(),
                CreativeIntentResponse.from(intent),
                CreativeStrategyPolicy.from(project).strategy());
    }

    public record CreativeIntentResponse(
            String premise,
            List<String> genres,
            String targetAudience,
            String protagonistBrief,
            String centralConflict,
            List<String> tones,
            Integer targetWords,
            String endingPreference,
            List<String> mustHave,
            List<String> avoid,
            List<String> stylePreferences,
            long version) {

        public static CreativeIntentResponse from(CreativeIntent intent) {
            if (intent == null) {
                return null;
            }
            return new CreativeIntentResponse(
                    intent.getPremise(),
                    intent.getGenres(),
                    intent.getTargetAudience(),
                    intent.getProtagonistBrief(),
                    intent.getCentralConflict(),
                    intent.getTones(),
                    intent.getTargetWords(),
                    intent.getEndingPreference(),
                    intent.getMustHave(),
                    intent.getAvoid(),
                    intent.getStylePreferences(),
                    intent.getRowVersion());
        }
    }
}
