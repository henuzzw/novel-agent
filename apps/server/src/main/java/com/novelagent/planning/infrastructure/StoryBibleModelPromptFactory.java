package com.novelagent.planning.infrastructure;

import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import org.springframework.stereotype.Component;

@Component
public class StoryBibleModelPromptFactory {
    public String systemPrompt() {
        return """
                你是长篇小说规划 Agent。把作者已确认的故事方向扩展成可执行、可审查的故事圣经。
                故事圣经必须能支撑目标篇幅，人物弧光、世界规则、关系变化和结局方向要互相一致。
                不得加入作者明确禁止的内容，不得改变已确认方向的核心承诺。
                作者列出的每条“必须包含”都要转化为明确的故事事实、人物关系、时序或空间规则，
                并逐条写入 hardConstraints，供大纲和正文阶段继续执行。
                输出对象包含 content 和 changeSummary；content 是完整故事圣经，changeSummary 是中文修改说明数组。
                只输出符合约定结构的 JSON，不输出 Markdown 或解释。
                """;
    }

    public String userPrompt(CreativeIntentSnapshot intent, StoryDirectionCandidate direction,
            StoryBibleContent previousBible, String instruction) {
        String revisionContext = previousBible == null
                ? "无。本次根据已确认方向重新生成。changeSummary 必须返回空数组。"
                : """
                  当前故事圣经：%s

                  必须在当前故事圣经基础上调整，只修改最新故事方向、创作意图或本次调整要求涉及的内容。
                  其余人物设定、世界规则、关系、硬约束和结局承诺保持稳定，并输出完整的新版本。
                  changeSummary 必须逐条概括实际改动及原因，不得罗列未变化内容；如果最终没有实质变化，
                  返回一条“未发现需要修改的内容，沿用原版本”。
                  """.formatted(previousBible);
        return """
                请为下面已确认的故事方向生成故事圣经。

                一句话创意：%s
                类型：%s
                目标读者：%s
                目标字数：%s
                基调：%s
                必须包含：%s
                禁止内容：%s

                已确认方向：%s
                故事前提：%s
                核心冲突：%s
                主角弧光：%s
                结构：%s
                结局方向：%s
                当前版本参考：%s
                本次调整要求：%s

                请逐条落实“必须包含”，不要合并到无法核对，也不要仅笼统概括。

                worldRules、supportingCharacters、relationshipDynamics、hardConstraints、openQuestions 均为字符串数组。
                supportingCharacters 每项用“姓名/身份：欲望；阻力；与主角关系”的完整文本表达。
                返回格式为 {"content":{...完整故事圣经...},"changeSummary":[]}。
                """.formatted(value(intent.premise()), intent.genres(), value(intent.targetAudience()),
                intent.targetWords(), intent.tones(), intent.mustHave(), intent.avoid(), direction.title(),
                direction.premise(), direction.centralConflict(), direction.protagonistArc(), direction.structure(),
                direction.endingDirection(), revisionContext, value(instruction));
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "未指定" : value.toString();
    }
}
