package com.novelagent.planning.application;

public final class ReaderExperiencePlanningGuide {
    private ReaderExperiencePlanningGuide() { }
    public static String rules() {
        return """

                【可追踪的承诺与伏笔规划】
                content.readerExperiencePlans 单独保存明确设计的读者承诺和伏笔，不靠后续猜测自然语言。
                每项包含 key（80 字符内英文数字下划线或短横线）、kind（PROMISE / FORESHADOW）、title、promise、setup、payoff、aftermath、plannedChapter。
                key 在同一版本内唯一；调整已有规划时保留原 key。最多 80 项，无明确规划可为空数组。
                promise 是读者期待，setup 是设计的铺垫，payoff 是计划兑现，aftermath 是计划余波；章号未知为 null。
                以上全部是未来规划，不是正文证据，不代表已埋设或已兑现；未知内容留空，不编造历史正文。
                openQuestions 是作者待确认事项，不自动包装成读者承诺；普通物品、每个章末钩子也不强行视作伏笔。
                大纲仅列自身新增或调整的规划，不照抄故事圣经台账制造重复记录；原圣经规划仍单独保留。
                """;
    }
}
