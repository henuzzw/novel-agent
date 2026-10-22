package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.modelaccess.application.StagePromptContributor;
import com.novelagent.planning.application.ScenePlanningGuide;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 按原有两种大纲输出结构补充章节文字块规则，不改变其他阶段的提示词。 */
@Component
@Order(20)
public class OutlinePromptContributor implements StagePromptContributor {
    @Override
    public String instructions(String workflow, JsonNode schema) {
        boolean chapterBlocks =
                schema != null
                        && (schema.at(
                                                "/properties/content/properties/arcs/items/properties/chapters/items/properties")
                                        .has("content")
                                || schema.at(
                                                "/properties/arcs/items/properties/chapters/items/properties")
                                        .has("content"));
        return chapterBlocks ? "\n" + ScenePlanningGuide.planningRules() : "";
    }
}
