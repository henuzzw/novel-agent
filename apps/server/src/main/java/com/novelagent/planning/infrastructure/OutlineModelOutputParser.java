package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.GeneratedOutline;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.OutlineContent;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class OutlineModelOutputParser {
    private final ObjectMapper mapper;
    public OutlineModelOutputParser(ObjectMapper mapper) { this.mapper = mapper; }

    public GeneratedOutline parse(ModelProvider provider, String raw) {
        try {
            ModelOutput output = mapper.readValue(strip(raw), ModelOutput.class);
            OutlineContent content = output.content();
            if (content.title() == null || content.title().isBlank() || content.arcs() == null
                    || content.arcs().isEmpty() || content.chapterCount() == 0) {
                throw new IllegalArgumentException("大纲缺少必要内容");
            }
            return new GeneratedOutline(provider.name(), content, safe(output.changeSummary()));
        }
        catch (Exception exception) { throw new IllegalArgumentException("模型返回的分层大纲格式不合法", exception); }
    }

    private static String strip(String value) {
        String result = value == null ? "" : value.trim();
        if (result.startsWith("```")) result = result.replaceFirst("^```(?:json)?\\s*", "").replaceFirst("\\s*```$", "");
        return result;
    }

    private static List<String> safe(List<String> values) {
        return values == null ? List.of() : values.stream()
                .filter(value -> value != null && !value.isBlank()).map(String::trim).toList();
    }

    private record ModelOutput(OutlineContent content, List<String> changeSummary) {
    }
}
