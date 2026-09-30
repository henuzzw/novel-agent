package com.novelagent.planning.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class StoryDirectionGeneratorRegistry {

    private final Map<ModelProvider, StoryDirectionGenerator> generators;

    public StoryDirectionGeneratorRegistry(List<StoryDirectionGenerator> generators) {
        Map<ModelProvider, StoryDirectionGenerator> byProvider = new EnumMap<>(ModelProvider.class);
        for (StoryDirectionGenerator generator : generators) {
            StoryDirectionGenerator previous = byProvider.put(generator.provider(), generator);
            if (previous != null) {
                throw new IllegalStateException("Duplicate story direction generator: " + generator.provider());
            }
        }
        this.generators = Map.copyOf(byProvider);
    }

    public StoryDirectionGenerator require(ModelProvider provider) {
        StoryDirectionGenerator generator = generators.get(provider);
        if (generator == null) {
            throw new IllegalArgumentException("不支持的模型提供方：" + provider);
        }
        return generator;
    }
}
