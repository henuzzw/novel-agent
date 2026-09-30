package com.novelagent.planning.infrastructure;

import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class StoryDirectionModelPromptFactory {

    public String systemPrompt() {
        return """
                你是长篇小说规划 Agent。根据作者创作意图生成三个实质不同的故事方向。
                三个方向至少在核心冲突、人物弧光或叙事结构之一存在显著区别。
                不得加入作者明确禁止的内容，不得只更换标题。
                作者列出的每一条“必须包含”都是项目级事实约束，三个候选方向都必须逐条落实。
                不得遗漏、改变关键含义，或只在输出中原样复述而不融入故事前提、冲突、结构和人物关系。
                只输出符合约定结构的 JSON，不输出 Markdown 或解释。
                每个方向必须包含 title、premise、centralConflict、protagonistArc、structure、
                endingDirection、audienceFit、strengths、risks、distinctiveFeatures。
                strengths、risks、distinctiveFeatures 均为字符串数组，questionsForAuthor 也是字符串数组。
                changeSummary 为本版相对当前版本的修改说明数组，使用简洁中文说明改了什么及原因。
                """;
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

                返回对象格式：
                {"directions":[{...三个方向...}],"questionsForAuthor":[],"changeSummary":[]}
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
