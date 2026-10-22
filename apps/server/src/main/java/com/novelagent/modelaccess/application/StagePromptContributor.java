package com.novelagent.modelaccess.application;

import com.fasterxml.jackson.databind.JsonNode;

/** 阶段所属模块提供附加执行规则；返回空串表示本次请求不适用，不在这里调用模型或访问存储。 */
public interface StagePromptContributor {
    String instructions(String workflow, JsonNode schema);
}
