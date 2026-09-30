package com.novelagent.planning.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class StoryBibleGeneratorRegistry {
    private final Map<ModelProvider, StoryBibleGenerator> generators;

    public StoryBibleGeneratorRegistry(List<StoryBibleGenerator> generators) {
        Map<ModelProvider, StoryBibleGenerator> values = new EnumMap<>(ModelProvider.class);
        generators.forEach(generator -> {
            if (values.put(generator.provider(), generator) != null) {
                throw new IllegalStateException("Duplicate story bible generator: " + generator.provider());
            }
        });
        this.generators = Map.copyOf(values);
    }

    public StoryBibleGenerator require(ModelProvider provider) {
        StoryBibleGenerator generator = generators.get(provider);
        if (generator == null) {
            throw new IllegalArgumentException("不支持的模型提供方：" + provider);
        }
        return generator;
    }
}
