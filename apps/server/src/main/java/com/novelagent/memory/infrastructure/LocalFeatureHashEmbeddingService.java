package com.novelagent.memory.infrastructure;

import com.novelagent.memory.application.TextEmbeddingService;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 本地文本向量。
 *
 * <p>用确定性的特征哈希构造本地向量，便于离线运行与流程测试。它不调用外部模型，不能等同于语言模型的语义嵌入质量。</p>
 */
@Component
@ConditionalOnProperty(name = "app.memory.embedding.provider", havingValue = "local", matchIfMissing = true)
public class LocalFeatureHashEmbeddingService implements TextEmbeddingService {
    private static final int DIMENSIONS = 1024;

    /**
     * 返回本实现的向量维度，必须与索引及调用方配置一致。
     */
    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    /**
     * 返回当前嵌入实现的模型标识，供索引来源与配置核对使用。
     */
    @Override
    public String modelName() {
        return "local-feature-hash-v2-1024";
    }

    /**
     * 将输入文本转换为本实现的向量，并遵循配置维度；外部实现失败不能返回伪造的成功结果。
     *
     * @param text 待渲染、检索或嵌入的文本，不自动成为正史事实。
     */
    @Override
    public float[] embed(String text) {
        float[] vector = new float[DIMENSIONS];
        if (text == null || text.isBlank()) return vector;
        String normalized = text.strip().toLowerCase();
        int codePoints = normalized.codePointCount(0, normalized.length());
        for (int size = 1; size <= 3; size++) {
            for (int start = 0; start + size <= codePoints; start++) {
                int begin = normalized.offsetByCodePoints(0, start);
                int end = normalized.offsetByCodePoints(begin, size);
                byte[] feature = normalized.substring(begin, end).getBytes(StandardCharsets.UTF_8);
                int hash = fnv1a(feature);
                int index = Math.floorMod(hash, DIMENSIONS);
                vector[index] += (hash & 1) == 0 ? 1.0f : -1.0f;
            }
        }
        normalize(vector);
        return vector;
    }

    private static int fnv1a(byte[] bytes) {
        int hash = 0x811c9dc5;
        for (byte value : bytes) {
            hash ^= value & 0xff;
            hash *= 0x01000193;
        }
        return hash;
    }

    private static void normalize(float[] vector) {
        double sum = 0;
        for (float value : vector) sum += value * value;
        if (sum == 0) return;
        float divisor = (float) Math.sqrt(sum);
        for (int i = 0; i < vector.length; i++) vector[i] /= divisor;
    }
}
