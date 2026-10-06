package com.novelagent.project.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.planning.infrastructure.CodexAppServerClient;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.domain.GlobalModelSettings;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class GlobalModelCatalogService {
    private final CodexAppServerClient codex;

    public GlobalModelCatalogService(CodexAppServerClient codex) {
        this.codex = codex;
    }

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
