package com.novelagent.ingest.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.modelaccess.application.StagePromptContributor;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/** 原文解析规则由导入模块维护，模型接入层不依赖导入适配器。 */
@Component
@Order(10)
public class ImportAnalysisPromptContributor implements StagePromptContributor {
    @Override
    public String instructions(String workflow, JsonNode schema) {
        return "IMPORT_SOURCE_ANALYSIS".equals(workflow)
                ? ImportAnalysisPrompt.WORLD_ASSESSMENT_RULES
                : "";
    }
}
