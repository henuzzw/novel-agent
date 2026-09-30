package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.StoryBibleContent;
import org.springframework.stereotype.Component;

@Component
public class OutlineModelPromptFactory {
    private final ObjectMapper mapper;

    public OutlineModelPromptFactory(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String systemPrompt() {
        return """
                你是长篇小说规划 Agent，负责生成全书、卷/幕、章节三级大纲。
                已发布故事圣经的世界规则、人物弧光、结局方向和硬约束是最高优先级；作者本次要求不能推翻这些硬约束。
                章节由人物目标驱动，事件有原因和后果，关系与冲突逐步发展。不要为了凑章节数凭空增加重复事件。
                字数是模糊容量参考：整书建议区间落在给定范围内，卷章字数允许随剧情自然浮动，不要求逐级精确相加。
                输出对象包含 content 和 changeSummary；content 是完整分层大纲，changeSummary 是中文修改说明数组。
                只输出符合约定结构的 JSON，不输出 Markdown、解释或思考过程。
                """;
    }

    public String userPrompt(StoryBibleContent bible, OutlineWordBudget budget, OutlineContent previousOutline,
            String instruction) {
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

                【依据优先级】
                1. 已发布故事圣经的明确事实、世界规则和硬约束。
                2. 与上述内容不冲突的作者本次要求。
                3. 调整模式下选定的基准大纲；只修正与前两项真正冲突的部分。
                openQuestions 是待确认问题，不得擅自写成已确立事实。

                【已发布故事圣经（完整 JSON）】
                %s

                【篇幅参考】
                目标字数：%d；整书建议范围：%d～%d 字。
                从头规划时可参考约 %d 卷、%d 章，单章通常 %d～%d 字。
                调整模式优先保留基准大纲的卷章数量，不为贴合建议数量扩写或删减。

                【作者本次要求】
                %s

                %s

                【输出检查】
                content 必须包含完整全书大纲，arcs 中每卷包含 chapters，章节编号从 1 连续递增。
                与故事圣经硬约束逐条核对；调整模式核对未受影响章节及 status 是否保持原样。
                返回格式：{"content":{...完整分层大纲...},"changeSummary":[]}。
                """.formatted(json(bible),
                budget.targetWords(), budget.acceptableMinWords(), budget.acceptableMaxWords(),
                budget.recommendedVolumeCount(), budget.recommendedChapterCount(),
                budget.recommendedChapterMinWords(), budget.recommendedChapterMaxWords(),
                instruction == null || instruction.isBlank() ? "无" : instruction.trim(), modeRules);
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
