package com.novelagent.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.StoryBibleContent;

/** Presentation of the saved step-7 projection, never a second creative interpretation. */
final class SnowflakeMaterialText {
    private SnowflakeMaterialText() { }

    static String render(StoryBibleContent materials, ObjectMapper mapper) {
        StringBuilder text = new StringBuilder();
        for (var character : materials.characterBlueprints()) {
            text.append("## ").append(character.name()).append("\n");
            append(text, mapper.valueToTree(character));
            text.append('\n');
        }
        text.append("## 世界与规则\n").append(materials.worldSetting()).append('\n');
        materials.worldRules().forEach(value -> text.append("- ").append(value).append('\n'));
        text.append("\n## 初始关系\n");
        materials.relationshipDynamics().forEach(value -> text.append("- ").append(value).append('\n'));
        text.append("\n## 伏笔与承诺规划（未发生）\n");
        materials.readerExperiencePlans().forEach(value -> { append(text, mapper.valueToTree(value)); text.append('\n'); });
        text.append("\n## 作者硬约束\n");
        materials.hardConstraints().forEach(value -> text.append("- ").append(value).append('\n'));
        text.append("\n## 待定事项\n");
        materials.openQuestions().forEach(value -> text.append("- ").append(value).append('\n'));
        return text.toString().strip();
    }

    private static void append(StringBuilder text, JsonNode node) {
        node.properties().forEach(entry -> {
            if (entry.getValue().isArray()) {
                text.append(label(entry.getKey())).append(":\n");
                entry.getValue().forEach(value -> text.append("- ").append(value.asText()).append('\n'));
            } else if (!entry.getValue().isNull() && !entry.getValue().asText().isBlank()) {
                text.append(label(entry.getKey())).append(": ").append(entry.getValue().asText()).append('\n');
            }
        });
    }

    private static String label(String key) {
        return switch (key) {
            case "name" -> "姓名"; case "role" -> "角色定位"; case "identity" -> "身份";
            case "appearance" -> "外貌"; case "background" -> "背景经历";
            case "externalPersonality" -> "外在性格"; case "internalPersonality" -> "内在性格";
            case "coreDesire" -> "核心欲望"; case "fear" -> "恐惧"; case "flaw" -> "弱点";
            case "values" -> "价值观"; case "speechStyle" -> "人物声口"; case "behaviorHabits" -> "行为习惯";
            case "abilitiesAndLimits" -> "能力与限制"; case "behaviorBoundaries" -> "行为底线";
            case "secret" -> "作者侧秘密"; case "characterArc" -> "成长弧线（规划）";
            case "openingState" -> "开篇状态"; case "initialRelationships" -> "初始关系";
            case "initialPossessions" -> "初始物品"; case "knowledgeBoundaries" -> "知识边界";
            case "gender" -> "性别"; case "ageDescription" -> "年龄";
            case "key" -> "计划标识"; case "kind" -> "类型"; case "title" -> "标题";
            case "promise" -> "读者期待"; case "setup" -> "铺设"; case "payoff" -> "预期兑现";
            case "aftermath" -> "后续影响"; case "plannedChapter" -> "计划兑现章节";
            default -> key;
        };
    }
}
