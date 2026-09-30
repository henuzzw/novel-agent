package com.novelagent.planning.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.planning.application.ModelProviderException;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

@Component
public class DeepSeekStructuredOutputClient {
    private final String apiKey;
    private final String model;
    private final RestClient restClient;

    public DeepSeekStructuredOutputClient(@Value("${app.ai.deepseek.api-key:}") String apiKey,
            @Value("${app.ai.deepseek.base-url}") String baseUrl,
            @Value("${app.ai.deepseek.model}") String model) {
        this.apiKey = apiKey;
        this.model = model;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    public String request(String schemaName, String instructions, String input, JsonNode schema, int maxOutputTokens) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("尚未配置 DeepSeek API Key");
        Map<String, Object> request = Map.of(
                "model", model, "instructions", instructions, "input", input,
                "reasoning", Map.of("effort", "none"),
                "text", Map.of("format", Map.of("type", "json_schema", "name", schemaName, "schema", schema)),
                "max_output_tokens", maxOutputTokens, "stream", false);
        JsonNode response;
        try {
            response = restClient.post().uri("/responses")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(request).retrieve().body(JsonNode.class);
        }
        catch (RestClientException exception) { throw new ModelProviderException("DeepSeek 请求失败，请稍后重试", exception); }
        if (response == null) throw new ModelProviderException("DeepSeek 未返回响应");
        String status = response.path("status").asText();
        if (!"completed".equals(status)) {
            String reason = response.at("/error/message").asText(response.at("/incomplete_details/reason").asText(status));
            throw new ModelProviderException("DeepSeek 生成未完成：" + reason);
        }
        String output = findOutputText(response.path("output"));
        if (output == null || output.isBlank()) throw new ModelProviderException("DeepSeek 未返回可用内容");
        return output;
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
