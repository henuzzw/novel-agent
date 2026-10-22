package com.novelagent.writing.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.modelaccess.application.ModelProviderException;
import com.novelagent.modelaccess.domain.CodexSessionPolicy;
import com.novelagent.modelaccess.application.StructuredModelGateway;
import com.novelagent.prompt.application.AgentPromptDefaults;
import com.novelagent.writing.application.ManuscriptLocalEditStore.Snapshot;
import com.novelagent.writing.domain.ManuscriptLocalEditSelection;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class ManuscriptLocalEditModel {
    private final StructuredModelGateway gateway;
    private final ObjectMapper mapper;

    public ManuscriptLocalEditModel(StructuredModelGateway gateway, ObjectMapper mapper) {
        this.gateway = gateway;
        this.mapper = mapper;
    }

    public String replace(UUID projectId, Snapshot source, ManuscriptLocalEditSelection selection,
            ModelProvider provider, String instruction) {
        String system = AgentPromptDefaults.system("MANUSCRIPT_LOCAL_EDIT");
        var data = mapper.createObjectNode();
        data.put("basis", source.context());
        data.put("sourceBody", source.rendered().body());
        data.put("selection", selection.text());
        data.put("offsetUtf16", selection.offset());
        data.put("occurrence", selection.occurrence());
        data.put("instruction", instruction);
        JsonNode schema = mapper.createObjectNode().put("type", "object").put("additionalProperties", false)
                .set("properties", mapper.createObjectNode().set("replacement", mapper.createObjectNode()
                        .put("type", "string").put("maxLength", 24000)));
        ((ObjectNode) schema).set("required", mapper.createArrayNode().add("replacement"));
        String output = gateway.request(projectId, "MANUSCRIPT_LOCAL_EDIT", provider, system, data.toString(), schema,
                "manuscript_local_edit", 12000, CodexSessionPolicy.NEW_THREAD);
        try {
            JsonNode value = mapper.readTree(output);
            if (value == null || !value.isObject() || value.size() != 1 || !value.path("replacement").isTextual()
                    || value.path("replacement").textValue().length() > 24000) {
                throw new IllegalArgumentException("局部编辑模型必须仅返回 replacement 文本");
            }
            return value.path("replacement").textValue();
        } catch (JsonProcessingException failure) {
            throw new ModelProviderException("局部编辑模型返回了无效 JSON", failure);
        }
    }
}
