package com.novelagent.planning.application;

public final class CharacterBlueprintGuide {
    private CharacterBlueprintGuide() { }

    public static String boundaries() {
        return """
                【人物底稿的使用边界】
                characterBlueprints 是随故事圣经版本保存的作者侧人物设计，不是动态正史状态。
                gender、ageDescription 分别记录明确性别和故事起点年龄或有依据的年龄范围；旧资料未给定时留空，不能仅凭姓名、外貌或年级推断确切年龄。
                openingState、initialRelationships、initialPossessions 描述第一章起点；后续章节必须以已发生正文与有效正史的变化为准，不能每章重置回开篇。
                secret、internalPersonality、characterArc 不代表视角人物已知或本章已经发生；未来弧光仅是计划，不提前兑现或泄露。
                knowledgeBoundaries 区分知道、不知道与误以为；物品只有已给定来源和转移依据才能使用。
                性格、衣着、外貌不能直接证明动机；用情境、选择与后果表现性格，反常行为需要依据，不把人物标签当不可变化的死规则。
                资料中心的独立人物档案是作者补充，不自动覆盖或合并冲突；与圣经硬约束或有效正史矛盾时指出冲突，不自行改事实。
                未知或待作者确认的信息不得擅自坐实，不为修补剧情新增能力、经历、道具或秘密。
                """;
    }
}
