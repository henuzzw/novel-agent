package com.novelagent.memory.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.memory.application.TextEmbeddingService;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 外部文本向量。
 *
 * <p>按兼容接口请求嵌入并检查响应维度。属于外部依赖调用，错误不能伪装为有效向量；模型与维度须匹配现有索引配置。</p>
 */
@Component
@ConditionalOnProperty(name = "app.memory.embedding.provider", havingValue = "remote")
public class OpenAiCompatibleEmbeddingService implements TextEmbeddingService {
    private final String apiKey;
    private final String model;
    private final int dimensions;
    private final RestClient restClient;

    public OpenAiCompatibleEmbeddingService(
            @Value("${app.memory.embedding.api-key:}") String apiKey,
            @Value("${app.memory.embedding.base-url}") String baseUrl,
            @Value("${app.memory.embedding.model}") String model,
            @Value("${app.memory.embedding.dimensions:1024}") int dimensions) {
        if (dimensions != 1024) {
            throw new IllegalArgumentException("当前 pgvector 列固定为 1024 维，请将 EMBEDDING_DIMENSIONS 设为 1024");
        }
        this.apiKey = apiKey;
        this.model = model;
        this.dimensions = dimensions;
        this.restClient = RestClient.builder().baseUrl(baseUrl).build();
    }

    /**
     * 返回本实现的向量维度，必须与索引及调用方配置一致。
     */
    @Override
    public int dimensions() {
        return dimensions;
    }

    /**
     * 返回当前嵌入实现的模型标识，供索引来源与配置核对使用。
     */
    @Override
    public String modelName() {
        return model + "-" + dimensions;
    }

    /**
     * 将输入文本转换为本实现的向量，并遵循配置维度；外部实现失败不能返回伪造的成功结果。
     *
     * @param text 待渲染、检索或嵌入的文本，不自动成为正史事实。
     */
    @Override
    public float[] embed(String text) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("已启用正式 Embedding，但未配置 EMBEDDING_API_KEY");
        }
        JsonNode response;
        try {
            response = restClient.post()
                    .uri("/embeddings")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + apiKey)
                    .body(Map.of(
                            "model", model,
                            "input", text == null ? "" : text,
                            "dimensions", dimensions,
                            "encoding_format", "float"))
                    .retrieve()
                    .body(JsonNode.class);
        } catch (RestClientException exception) {
            throw new IllegalStateException("Embedding 服务请求失败", exception);
        }
        JsonNode values = response == null ? null : response.at("/data/0/embedding");
        if (values == null || !values.isArray() || values.size() != dimensions) {
            throw new IllegalStateException("Embedding 服务未返回 " + dimensions + " 维向量");
        }
        float[] vector = new float[dimensions];
        for (int i = 0; i < dimensions; i++) {
            vector[i] = (float) values.get(i).asDouble();
        }
        return vector;
    }
}
