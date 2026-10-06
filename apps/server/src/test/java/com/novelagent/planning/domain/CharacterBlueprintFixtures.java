package com.novelagent.planning.domain;

import java.util.List;

public final class CharacterBlueprintFixtures {
    private CharacterBlueprintFixtures() { }

    public static CharacterBlueprint character(String name) {
        return new CharacterBlueprint(name, "PROTAGONIST", "学生，想保住学习时间", "细框眼镜", "长期被要求懂事",
                "客气", "怕拒绝别人", "希望被明确选择", "失去朋友", "回避表态", "不轻易失信",
                "短句，常先答应再解释", "紧张时扶眼镜", "擅长数学，但不擅长表达感情", "不公开他人的纸条",
                "尚未承认自己的偏爱", "计划学会明确选择，尚未发生", "选座前尚未知道对方的心意",
                List.of("与同学相识一年，互相讲题"), List.of("眼镜由家长购买，目前本人持有"),
                List.of("不知道同学写了未送出的纸条"));
    }

    public static StoryBibleContent bible(List<CharacterBlueprint> characters) {
        return new StoryBibleContent("学生面对选择", "承担", "校园", List.of("不能偷拍"), "江澈：学生", "学会选择",
                List.of("许冬：同学"), List.of("从朋友开始"), "回避选择", "失去信任", "克制", "主动承担",
                List.of("保留选座事件"), List.of("转学信息待确认"), characters);
    }
}
