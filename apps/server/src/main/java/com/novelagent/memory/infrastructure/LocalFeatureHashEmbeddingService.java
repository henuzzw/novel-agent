package com.novelagent.memory.infrastructure;

import com.novelagent.memory.application.TextEmbeddingService;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.memory.embedding.provider", havingValue = "local", matchIfMissing = true)
public class LocalFeatureHashEmbeddingService implements TextEmbeddingService {
    private static final int DIMENSIONS = 1024;

    @Override
    public int dimensions() {
        return DIMENSIONS;
    }

    @Override
    public String modelName() {
        return "local-feature-hash-v2-1024";
    }

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
