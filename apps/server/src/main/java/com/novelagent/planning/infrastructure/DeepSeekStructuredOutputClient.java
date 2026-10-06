package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.planning.application.ModelProviderException;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.agent.application.AgentRunRecorder.ModelResult;
import com.novelagent.agent.application.AgentRunRecorder.Usage;
import com.novelagent.agent.application.AgentRunRecorder.UsageCarrier;
import com.novelagent.project.application.GlobalModelSettingsService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

@Component
public class DeepSeekStructuredOutputClient {
    private final String apiKey;
    private final RestClient restClient;
    private final GlobalModelSettingsService settings;

    @Autowired
    public DeepSeekStructuredOutputClient(@Value("${app.ai.deepseek.api-key:}") String apiKey,
            @Value("${app.ai.deepseek.base-url}") String baseUrl,
            GlobalModelSettingsService settings) {
        this(apiKey, RestClient.builder().baseUrl(baseUrl)
                .requestFactory(new org.springframework.http.client.JdkClientHttpRequestFactory()).build(), settings);
    }

    DeepSeekStructuredOutputClient(String apiKey, RestClient restClient, GlobalModelSettingsService settings) {
        this.apiKey = apiKey;
        this.settings = settings;
        this.restClient = restClient;
    }

    public String request(String schemaName, String instructions, String input, JsonNode schema, int maxOutputTokens) {
        return request(schemaName, instructions, input, schema, maxOutputTokens, effectiveSettings()).output();
    }

    EffectiveSettings effectiveSettings() {
        var value = settings.get();
        return new EffectiveSettings(ModelProvider.DEEPSEEK, value.deepSeekModel(), "none", value.version());
    }

    public ResponseResult request(String schemaName, String instructions, String input, JsonNode schema,
            int maxOutputTokens, EffectiveSettings frozenSettings) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("尚未配置 DeepSeek API Key");
        Map<String, Object> request = Map.of(
                "model", frozenSettings.model(), "instructions", instructions, "input", input,
                "reasoning", Map.of("effort", "none"),
                "text", Map.of("format", Map.of("type", "json_schema", "name", schemaName, "schema", schema)),
                "max_output_tokens", maxOutputTokens, "stream", false);
        JsonNode response;
        try {
            response = restClient.post().uri("/responses")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(request).retrieve().body(JsonNode.class);
        }
        catch (RestClientResponseException exception) {
            int statusCode = exception.getStatusCode().value();
            String category = statusCode == 401 || statusCode == 403 ? "AUTHENTICATION"
                    : statusCode == 429 ? "RATE_LIMIT" : "HTTP_ERROR";
            throw new ModelProviderException("DeepSeek 请求失败：" + category, exception);
        }
        catch (RestClientException exception) {
            throw new ModelProviderException("DeepSeek 请求失败：NETWORK", exception);
        }
        if (response == null) throw new ModelProviderException("DeepSeek 未返回响应");
        Usage usage = Usage.from(response.path("usage"), false);
        String status = response.path("status").asText();
        if (!"completed".equals(status)) {
            throw new ResponseFailure("DeepSeek 生成未完成", usage);
        }
        String output = findOutputText(response.path("output"));
        if (output == null || output.isBlank()) throw new ResponseFailure("DeepSeek 未返回可用内容", usage);
        return new ResponseResult(output, usage);
    }

    public record ResponseResult(String output, Usage usage) implements ModelResult { }

    private static final class ResponseFailure extends ModelProviderException implements UsageCarrier {
        private final Usage usage;

        private ResponseFailure(String message, Usage usage) {
            super(message);
            this.usage = usage;
        }

        @Override public Usage usage() { return usage; }
    }

    private static String findOutputText(JsonNode output) {
        if (!output.isArray()) return null;
        for (JsonNode item : output) {
            if (!"message".equals(item.path("type").asText())) continue;
            for (JsonNode content : item.path("content")) {
                if ("output_text".equals(content.path("type").asText())) return content.path("text").asText(null);
            }
        }
        return null;
    }
}
