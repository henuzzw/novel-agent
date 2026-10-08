package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.CharacterBlueprintGuide;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.project.application.CreativeStrategyGuide;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import org.springframework.stereotype.Component;

@Component
public class OutlineModelPromptFactory {
    private final ObjectMapper mapper;

    public OutlineModelPromptFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String systemPrompt() {
        return com.novelagent.prompt.application.AgentPromptDefaults.system("OUTLINE");
    }

    public String userPrompt(StoryBibleContent bible, OutlineWordBudget budget, OutlineContent previousOutline,
            String instruction) {
        return userPrompt(bible, budget, previousOutline, instruction,
                CreativeStrategyPolicy.of(CreativeStrategy.STANDARD));
    }

    public String userPrompt(StoryBibleContent bible, OutlineWordBudget budget, OutlineContent previousOutline,
            String instruction, CreativeStrategyPolicy policy) {
        String modeRules = previousOutline == null
                ? """
                  【生成方式：从头规划】
                  依据故事圣经和作者要求建立完整大纲，不参考任何旧版。
                  新规划的章节 status 全部设为 PLANNED；changeSummary 返回空数组。
                  """
                : """
                  【生成方式：基于选定版本调整】
                  以下 JSON 是选定的完整基准大纲，而非摘要。以它为底稿输出完整新版本，不只输出差异。

                  选定的基准大纲：
                  %s

                  【调整原则】
                  先在内部定位本次要求或新故事圣经实际影响的字段、卷和章节，再修改这些位置；不得借机整体重写。
                  未受影响的章节编号、标题、视角、目标、核心事件、揭示、钩子、因果顺序、伏笔和结局路线保持原样。
                  默认保留原有卷数、章节数和每章 status；尤其不得把已发生的 OCCURRED 章节改成 PLANNED。
                  只有作者明确要求或故事圣经硬约束确实要求时，才调整相应结构；篇幅参考本身不是增删章节的理由。
                  changeSummary 用简洁中文逐条说明实际改动的卷、章节或字段及原因，不得声称未发生的修改。
                  如果没有实质变化，沿用基准内容并返回一条“未发现需要修改的内容，沿用原版本”。
                  """.formatted(json(previousOutline));
        return """
                【任务】
                生成完整分层大纲。

                【作者本次要求（本轮修改重点）】
                %s
                以上为作者填写的本轮要求，不含系统生成的项目策略；空值“无”不表示作者授权整体重写。
                调整模式下先落实与故事圣经硬约束不冲突的本次要求，再检查哪些旧版内容必须改变；不要因此改写无关章节。

                【依据优先级】
                1. 不可越过的边界：已发布故事圣经的明确事实、世界规则、人物弧光、结局方向和硬约束，以及已发生 OCCURRED 章节与已确认事实。
                2. 与上述边界不冲突的作者本轮明确要求，决定修改目标、开场和叙事选择。
                3. 系统生成的项目创作策略，只在前两项范围内优化结构和阅读期待，不覆盖作者选择。
                4. 调整模式下选定的基准大纲；除作者明确授权或有效上游约束实际影响的部分外保持原样，不能仅因策略建议改变而重写。
                作者要求与硬约束冲突时，不悄悄改事实；调整模式在 changeSummary 说明未执行的要求、冲突依据及需作者先修改的上游，新规划在相关字段说明限制，不增加输出字段。
                openQuestions 是待确认问题，不得擅自写成已确立事实。
                developmentNotes 是雪花法自由文本底稿，不是另一套硬约束；作者修订后的圣经明确字段、硬约束和已确认事实优先，冲突底稿不采用。

                【项目创作策略（系统辅助规则，不是作者原文）】
                %s
                策略与作者明确选择不一致时，以不违反上游边界的作者要求为准；不能为了套用强开篇删除作者指定的成年开场、回忆框架或慢热安排。
                若作者指定照片缺失等回忆入口，保留入口，让已知线索引出核实、寻找或判断的当前问题并连接回忆；无法建立依据时说明限制，不虚构删除者、动机或危机。

                【已发布故事圣经（完整 JSON）】
                %s

                【篇幅参考】
                目标字数：%d；整书建议范围：%d～%d 字。
                从头规划时可参考约 %d 卷、%d 章，单章通常 %d～%d 字。
                调整模式优先保留基准大纲的卷章数量，不为贴合建议数量扩写或删减。

                %s

                【场景行动与读者体验设计】
                按人物底稿的欲望、恐惧、能力限制和行为底线设计行动；在相关章节字段写出触发、选择与代价，不临时改性格来迁就事件。
                沿用圣经已确定的具体姓名；从人物的关键经历、生活目标与内在矛盾推演选择，将关系双方的诉求落实为行动，不把人物重新简化成“主角/同伴”的功能标签。
                人物背景通过影响本章选择的情境进入剧情，不为展示档案安排无关生平说明，也不在大纲阶段另造过去或更换名字。
                开篇关系和物品随情节演变，不每章重置；秘密与未来弧光只在有依据、允许揭示的章节落实。
                在现有 objective/coreEvent/reveal/endingHook 文本内表达“承诺 / 铺垫依据 / 本章兑现 / 余波”，不增加输出字段。
                主要节拍写清起点、意图、阻力或信息差、行动、结束变化与重要依据；引用输入中已有的来源或章号，未知依据待确认，不编造正文证据。
                大兑现有前文承诺与铺垫，小回报回应本章问题；变化可以发生在关系、认知、处境、资源、行动方向或读者掌握的信息中。
                按题材选择关系确认、悬疑公平揭示、成长的选择与代价、日常理解或行动突破，不靠围观夸赞、反派降智、临时能力或巧合救场。
                不新编人物能力、道具权限、信息来源或帮助方向来修补因果；未来揭示不写成人物已知事实。
                结尾钩子来自本章结果，不能代替当章兑现；伏笔强化应增加信息或影响选择，不连续重复同一铺垫、同型钩子或场景功能。
                安静章、压抑章和悲剧章不强制正向快感，过渡章可标明主要积累；长期承诺不要求逐章兑现，不对所有章节设置反转、回报或钩子的硬配额；新规划第一章按系统开篇目标设计有依据的反转与题材回报。

                【整份大纲的前三章短弧】
                以独立“项目创作策略”区块中服务端传入的 policy 为准，不从作者原文推断项目配置；STANDARD 或未明确提供 FANQIE_GRIPPING 时不强制完整前三章短弧，但新规划第一章同样执行系统默认的吸引力、反转与题材回报目标；作者明确的节奏选择仍优先。
                仅 FANQIE_GRIPPING 下，从头规划或获授权调整前三章时，将整份大纲中的第 1～3 章一起设计为“开场问题 -> 主角行动 -> 阻力与代价 -> 第一轮兑现 -> 更长线目标”。
                第一章：开头进入主角具体处境与迫切问题，主角可见行动遭遇阻力，章内取得第一次真实回报或不可逆变化；先有进展再留下由行动引出的具体问题。
                第二章：承接第一章行动的后果与代价，升级阻力或使信息翻面，回应第一章一个具体期待；不重复设定介绍、心理结论或同型冲突。
                第三章：用前两章已有铺垫完成核心期待的阶段兑现，明确已兑现与长期未兑现的承诺，展示余波并连接全书主线。
                三章的目标、回报和钩子落到同一份大纲的现有章节字段，新人物与规则应作用于人物选择，不以只抛悬念或长篇背景代替因果推进。
                该规则只用于本次获授权的新规划或受影响部分，不自动改写旧版未受影响大纲，也不为统一格式改写旧字段；仅切换 policy 不构成重写授权。
                OCCURRED 章节及已确认事实不得为开篇强度自动改写；既有素材不足时指出限制交作者决定，不虚构补齐，不改变既有 status。
                作者明确选择慢热文学叙事时保留该选择；以上是创作设计建议，不是平台审核标准或流量保证。

                【输出检查】
                content 必须包含完整全书大纲，arcs 中每卷包含 chapters，章节编号从 1 连续递增。
                逐条核对开头的作者本次要求是否落实；与故事圣经硬约束冲突时以圣经为准，不要悄悄改写硬约束。
                再核对是否保留作者指定的开场与叙事框架；不得用项目策略取代本轮要求，不因作者保留回忆框架就判定必须换场。
                调整模式再对照选定基准大纲：未受影响的卷章、因果顺序和 status 必须保持原样，changeSummary 只列实际改动及原因。
                返回格式：{"content":{...完整分层大纲...},"changeSummary":[]}。
                """.formatted(instruction == null || instruction.isBlank() ? "无" : instruction.trim(),
                CreativeStrategyGuide.render(policy), json(bible),
                budget.targetWords(), budget.acceptableMinWords(), budget.acceptableMaxWords(),
                budget.recommendedVolumeCount(), budget.recommendedChapterCount(),
                budget.recommendedChapterMinWords(), budget.recommendedChapterMaxWords(),
                modeRules) + com.novelagent.planning.application.ReaderExperiencePlanningGuide.rules()
                + CreativeStrategyGuide.outlineRules()
                + com.novelagent.planning.application.ScenePlanningGuide.planningRules();
    }

    private String json(Object value) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("大纲 Prompt 输入无法序列化", exception);
        }
    }
}
