package com.novelagent.writing.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.canon.application.EntityCatalogContext;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ManuscriptContent;
import org.springframework.stereotype.Component;

@Component
class WritingPromptFactory {
    private static final String MANUSCRIPT_STYLE_RULES = """
            \n写作质感要求：
            1. 以具体场景为基本单位，通过动作、对话、停顿、选择、物件和环境变化呈现人物关系；能让读者自行理解的内容，不再由叙述者解释一遍。
            2. 严格服从故事圣经中的叙事风格和视角。不要擅自写成散文、影评、人生感悟或案件复盘，也不要为了显得深刻频繁提炼主题。
            3. 控制“不是……而是……”“直到后来才明白”“真正……的是”“那一刻我才意识到”等总结句，以及连续反问、整齐排比、同义反复和独句成段；仅在人物语气和情境确实需要时使用。
            4. 比喻必须来自当前人物熟悉的生活经验，并且少而准确。避免连续使用“像、仿佛、似乎”，避免抽象、万能或只为华丽而存在的比喻。
            5. 对话要符合人物年龄、关系、性格和当时目的，允许口语、省略、答非所问、误解和停顿；不要让人物轮流完整表达观点或替作者总结主题。
            6. 段落长短由场景节奏决定，不机械地一两句一段。重要情绪先写可观察的反应和行动，不直接贴情绪标签，不反复说明同一心理。
            7. 保留必要的日常过程、尴尬、犹豫和信息差，不把每个细节都设计成伏笔，不让每段末尾都承担金句、转折或悬念。
            8. 使用自然、准确、符合时代与人物身份的中文。避免网络写作套话、营销文案腔、翻译腔，以及超出人物认知范围的成熟判断。
            9. 在输出前静默检查正文：删除重复解释、空泛议论、无依据升华和可互换的套话；不得输出检查过程、写作说明或“作为 AI”等元话语。
            """;

    private final ObjectMapper mapper;
    private final CharacterNameService names;
    private final CharacterProfileService profiles;

    WritingPromptFactory(ObjectMapper mapper, CharacterNameService names, CharacterProfileService profiles) {
        this.mapper = mapper;
        this.names = names;
        this.profiles = profiles;
    }

    Prompt contract(java.util.UUID projectId, StoryBibleContent bible, OutlineArc arc, ChapterPlan chapter,
            NovelMemoryContext memory, String instruction) {
        return rendered(projectId, new Prompt(
                "你是小说章节策划 Agent。只依据故事圣经、所在卷和章节计划制定可执行合同，禁止新增冲突设定。",
                "请生成章节合同。字数是建议区间，不是硬性指标。\n故事圣经：" + json(bible)
                        + "\n人物档案：" + profiles.promptContext(projectId)
                        + "\n所在卷：" + json(arc) + "\n章节计划：" + json(chapter)
                        + "\n长期记忆：" + memory.toPromptText() + "\n作者要求：" + value(instruction)));
    }

    Prompt manuscript(java.util.UUID projectId, StoryBibleContent bible, OutlineArc arc, ChapterPlan chapter,
            ChapterContractContent contract, NovelMemoryContext memory, String instruction) {
        return manuscript(projectId, bible, arc, chapter, contract, memory, null, instruction);
    }

    Prompt manuscript(java.util.UUID projectId, StoryBibleContent bible, OutlineArc arc, ChapterPlan chapter,
            ChapterContractContent contract, NovelMemoryContext memory, ManuscriptContent previousManuscript,
            String instruction) {
        String revisionContext = previousManuscript == null
                ? "无。本次重新创作正文，changeSummary 必须返回空数组。"
                : """
                  当前正文版本：%s
                  必须以当前正文为基础修改。只改动作者要求、最新章节合同或上游设定真正影响的内容；
                  未受影响的叙述、场景顺序、对话、细节和文字表达尽量原样保留，不得借机整体重写。
                  changeSummary 使用简洁中文逐条说明实际修改及原因，不得罗列未变化内容；
                  如果没有实质变化，返回一条“未发现需要修改的内容，沿用原版本”。
                  """.formatted(json(previousManuscript));
        return rendered(projectId, new Prompt(
                "你是长篇小说正文写作 Agent。严格执行章节合同，保持人物、视角和事实连续性。"
                        + "你的首要任务是写出可信、具体、具有个人观察的小说场景，不炫技，不替读者总结。"
                        + "只输出符合结构约束的 JSON，其中 content 是完整可编辑正文，changeSummary 是中文修改说明数组。",
                "请创作本章正文。建议字数区间允许按情节自然浮动，不要为凑字数灌水。\n故事圣经：" + json(bible)
                        + "\n人物档案：" + profiles.promptContext(projectId)
                        + "\n所在卷：" + json(arc) + "\n章节计划：" + json(chapter)
                        + "\n章节合同：" + json(contract) + "\n长期记忆：" + memory.toPromptText()
                        + "\n当前版本参考：" + revisionContext
                        + "\n作者要求：" + value(instruction)
                        + MANUSCRIPT_STYLE_RULES
                        + "\n返回格式：{\"content\":{...完整正文...},\"changeSummary\":[]}"));
    }

    Prompt review(java.util.UUID projectId, StoryBibleContent bible, ChapterContractContent contract,
            ManuscriptContent manuscript, NovelMemoryContext memory, EntityCatalogContext entityCatalog,
            String instruction) {
        return rendered(projectId, new Prompt(
                "你是小说一致性审稿与记忆抽取 Agent。检查章节合同遵循、人物连续性、知识边界、因果与节奏；只抽取正文明确成立的候选事实。",
                "审查正文并抽取候选事实。BLOCKING 只用于明确违反硬约束或连续性的错误。每条结论必须引用正文中的简短证据。"
                        + "候选事实必须选择 ENTITY_UPSERT、EVENT_CREATE、STATE_CHANGE、RELATION_CHANGE、KNOWLEDGE_CHANGE、FORESHADOW_CHANGE 之一，"
                        + "并填写该类型对应的 payload。payload 必须输出全部字段，只填写当前事实类型需要的字段，其余字段填 null；"
                        + "STATE_CHANGE 使用 stateEntityType（只能是 CHARACTER 或 ITEM），ENTITY_UPSERT 才使用 entityType；"
                        + "不得根据常识补写正文没有明确成立的事实。\n故事圣经："
                        + json(bible) + "\n人物档案：" + profiles.promptContext(projectId)
                        + "\n章节合同：" + json(contract) + "\n正文：" + json(manuscript)
                        + "\n实体候选目录：" + json(entityCatalog)
                        + "\n实体消歧规则：优先使用章节相关、最近出现、标准名或别名匹配的实体 ID；"
                        + "确定性不足时 ID 填 null，不得编造目录中不存在的 UUID。"
                        + "\n长期记忆：" + memory.toPromptText() + "\n作者要求：" + value(instruction)));
    }

    private Prompt rendered(java.util.UUID projectId, Prompt prompt) {
        return new Prompt(names.render(projectId, prompt.system()), names.render(projectId, prompt.user()));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("写作 Prompt 输入无法序列化", exception);
        }
    }

    private static String value(String value) {
        return value == null || value.isBlank() ? "无" : value;
    }

    record Prompt(String system, String user) {
    }
}
