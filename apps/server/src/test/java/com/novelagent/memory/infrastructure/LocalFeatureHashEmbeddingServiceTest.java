package com.novelagent.memory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LocalFeatureHashEmbeddingServiceTest {
    private final LocalFeatureHashEmbeddingService service = new LocalFeatureHashEmbeddingService();

    @Test
    void producesStableNormalizedVectors() {
        float[] first = service.embed("林夏发现走廊里的旧钥匙");
        float[] second = service.embed("林夏发现走廊里的旧钥匙");

        assertThat(first).hasSize(1024).containsExactly(second);
        double norm = 0;
        for (float value : first) norm += value * value;
        assertThat(Math.sqrt(norm)).isBetween(0.9999, 1.0001);
    }

    @Test
    void givesRelatedTextMoreSharedSignal() {
        float[] query = service.embed("林夏找到了旧钥匙");
        assertThat(cosine(query, service.embed("林夏发现一把旧钥匙")))
                .isGreaterThan(cosine(query, service.embed("天气晴朗适合远足")));
    }

    private static double cosine(float[] left, float[] right) {
        double result = 0;
        for (int i = 0; i < left.length; i++) result += left[i] * right[i];
        return result;
    }
}
