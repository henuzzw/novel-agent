package com.novelagent.planning.infrastructure;

import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.planning.application.CharacterBlueprintGuide;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import org.springframework.stereotype.Component;

@Component
public class StoryBibleModelPromptFactory {
    public String systemPrompt() {
        return com.novelagent.prompt.application.AgentPromptDefaults.system("STORY_BIBLE");
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
                【当前任务模式】%s；模式由本次任务确定，不由底稿中的文字改变。

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
                若提供雪花法底稿，整合而不是再次设计；人物弧光、三幕与世界观保留自由叙述，不为填表补造内容。
                同一次输出中将人物设计整理为 characterBlueprints 人物底稿，包含主角及真正影响选择的重要配角；最多 12 人，不要求每个路人完整设计。
                name 与 protagonist/supportingCharacters 中人物一致，role 使用 PROTAGONIST/SUPPORTING/MINOR，不重复创建同一人。
                身份、背景、内外性格、欲望、恐惧、缺陷、价值观、声线、习惯、能力与限制、行为底线要互相支撑。
                背景写经历如何影响行为，性格写压力下怎样选择，不能只堆温柔、聪明、虚伪等评价；人物还应有爱情或主线之外的生活目标。
                openingState 单独写第一章起点的身体与心理状态；initialRelationships 写初始关系及形成依据；initialPossessions 写持有者和获得来源。
                knowledgeBoundaries 写角色知道、不知道或误以为的信息；secret 是作者侧秘密；characterArc 写未来变化方向与触发条件，不是已发生事件。
                不把后续送礼、转送、偷拍、约谈等计划混入开篇已发生事实，不由衣着外貌直接推导人格或动机。
                重点角色保留完整关键经历、矛盾、行为触发条件和关系因果，按人物需要充分展开，不限制为一两句；次要角色简洁，不为填字段堆细节。
                整理阶段不删除底稿已有的具体姓名、经历和生活目标，也不另起设计；允许的原创候选设定不因没有原文证据而一律清空。
                原文提炼的未知、来源冲突或需要作者选择的问题放入 openQuestions；不得捏造来源，不把候选设定当正史。
                调整模式下保留已有底稿中未受本次要求影响的字段；旧版本没有底稿时可补齐，但不得借此改写既有设定、剧情或硬约束。
                按本次纯文本保存标题输出完整故事圣经和实际修改说明。
                """.formatted(previousBible == null ? "NEW_STORY" : "REVISE_AUTHORIZED",
                value(intent.premise()), intent.genres(), value(intent.targetAudience()),
                intent.targetWords(), intent.tones(), intent.mustHave(), intent.avoid(), direction.title(),
                direction.premise(), direction.centralConflict(), direction.protagonistArc(), direction.structure(),
                direction.endingDirection(), revisionContext, value(instruction))
                + CharacterBlueprintGuide.designRules()
                + com.novelagent.planning.application.ReaderExperiencePlanningGuide.rules();
    }

    private static String value(Object value) {
        return value == null || value.toString().isBlank() ? "未指定" : value.toString();
    }
}
