package com.novelagent.memory.application;

import com.novelagent.planning.application.ModelProvider;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "app.ai.context")
public class ModelContextProperties {
    private Map<ModelProvider, Capacity> models = defaults();

    public Map<ModelProvider, Capacity> getModels() {
        return models;
    }

    public void setModels(Map<ModelProvider, Capacity> models) {
        this.models = models;
    }

    public Capacity require(ModelProvider provider) {
        Capacity capacity = models.get(provider);
        if (capacity == null || capacity.contextWindowTokens <= 0 || capacity.safetyMarginTokens < 0) {
            throw new IllegalStateException("未正确配置模型上下文容量：" + provider);
        }
        return capacity;
    }

    private static Map<ModelProvider, Capacity> defaults() {
        Map<ModelProvider, Capacity> values = new EnumMap<>(ModelProvider.class);
        values.put(ModelProvider.LOCAL_CODEX, new Capacity(128000, 4000));
        values.put(ModelProvider.DEEPSEEK, new Capacity(64000, 4000));
        values.put(ModelProvider.LOCAL_TEMPLATE, new Capacity(32000, 2000));
        return values;
    }

    public static class Capacity {
        private int contextWindowTokens;
        private int safetyMarginTokens;

        public Capacity() {
        }

        public Capacity(int contextWindowTokens, int safetyMarginTokens) {
            this.contextWindowTokens = contextWindowTokens;
            this.safetyMarginTokens = safetyMarginTokens;
        }

        public int getContextWindowTokens() {
            return contextWindowTokens;
        }

        public void setContextWindowTokens(int contextWindowTokens) {
            this.contextWindowTokens = contextWindowTokens;
        }

        public int getSafetyMarginTokens() {
            return safetyMarginTokens;
        }

        public void setSafetyMarginTokens(int safetyMarginTokens) {
            this.safetyMarginTokens = safetyMarginTokens;
        }
    }
}
