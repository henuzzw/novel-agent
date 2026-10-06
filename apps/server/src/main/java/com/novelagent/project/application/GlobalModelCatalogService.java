package com.novelagent.project.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.planning.infrastructure.CodexAppServerClient;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.domain.GlobalModelSettings;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 模型目录。
 *
 * <p>从本地 Codex 能力读取可选模型及强度，并校验保存的组合是否支持。目录查询不是一次小说生成；读取失败不得伪造支持列表。</p>
 */
@Service
public class GlobalModelCatalogService {
    private final CodexAppServerClient codex;

    public GlobalModelCatalogService(CodexAppServerClient codex) {
        this.codex = codex;
    }

    /**
     * 读取当前 Codex 接入返回的可见模型及支持强度，空目录视为不可用，不伪造支持列表。
     *
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    public List<ModelOption> chatGptModels() {
        List<ModelOption> options = new ArrayList<>();
        for (JsonNode model : codex.listModels()) {
            if (model.path("hidden").asBoolean(false)) continue;
            List<String> efforts = new ArrayList<>();
            for (JsonNode effort : model.path("supportedReasoningEfforts")) {
                String value = effort.path("reasoningEffort").asText();
                if (!value.isBlank()) efforts.add(value);
            }
            String id = model.path("model").asText(model.path("id").asText());
            if (!id.isBlank() && !efforts.isEmpty()) {
                options.add(new ModelOption(id, model.path("displayName").asText(id), List.copyOf(efforts),
                        model.path("defaultReasoningEffort").asText(efforts.getFirst())));
            }
        }
        if (options.isEmpty()) throw new IllegalStateException("当前 ChatGPT 接入未返回可用模型");
        return List.copyOf(options);
    }

    /**
     * 核对所选模型与强度等组合是否合法；供应商不支持时拒绝保存，而不是静默替换模型。
     *
     * @param value 当前业务对象或作者编辑值，具体类型由方法签名确定。
     */
    public GlobalModelSettings validate(GlobalModelSettings value) {
        if (value.provider() == ModelProvider.LOCAL_CODEX) {
            boolean supported = chatGptModels().stream().anyMatch(option -> option.model().equals(value.codexModel())
                    && option.efforts().contains(value.codexEffort()));
            if (!supported) throw new IllegalArgumentException("当前 ChatGPT 接入不支持所选模型或推理强度");
        }
        return value;
    }

    public record ModelOption(String model, String label, List<String> efforts, String defaultEffort) { }
}
