package com.novelagent.planning.infrastructure;

import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class StoryDirectionModelPromptFactory {

    public String systemPrompt() {
        return com.novelagent.prompt.application.AgentPromptDefaults.system("STORY_DIRECTION");
    }

    public String userPrompt(CreativeIntentSnapshot intent, List<StoryDirectionCandidate> previousDirections,
            String authorInstruction) {
        String revisionContext = previousDirections == null || previousDirections.isEmpty()
                ? "无。本次从创作意图重新设计。changeSummary 必须返回空数组。"
                : """
                  下面是当前版本的三个故事方向：
                  %s

                  本次必须以当前版本为基础调整。保留未被“本次调整要求”和最新创作意图影响的核心冲突、
                  人物弧光、结构、结局承诺与各候选之间的差异；不要借机整体重写。仍需输出三个完整候选方向。
                  changeSummary 必须逐条概括实际修改的字段或设定，不得罗列未变化内容；如果最终没有实质变化，
                  返回一条“未发现需要修改的内容，沿用原版本”。
                  """.formatted(previousDirections);
        return """
                请生成三个故事方向。

                一句话创意：%s
                小说类型：%s
                目标读者：%s
                主角：%s
                核心冲突：%s
                故事基调：%s
                目标字数：%s
                结局偏好：%s
                必须包含：%s
                禁止内容：%s
                风格偏好：%s
                当前版本参考：%s
                本次调整要求：%s

                生成前逐条检查“必须包含”。每个候选方向都要让这些事实在 premise、centralConflict、
                protagonistArc、structure、endingDirection 或 distinctiveFeatures 中得到具体体现。

                按本次纯文本保存标题输出三个完整故事方向、待确认问题和实际修改说明。
                """.formatted(
                value(intent.premise()),
                intent.genres(),
                value(intent.targetAudience()),
                value(intent.protagonistBrief()),
                value(intent.centralConflict()),
                intent.tones(),
                intent.targetWords(),
                value(intent.endingPreference()),
                intent.mustHave(),
                intent.avoid(),
                intent.stylePreferences(),
                revisionContext,
                value(authorInstruction));
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "未指定" : value.toString();
    }
}
