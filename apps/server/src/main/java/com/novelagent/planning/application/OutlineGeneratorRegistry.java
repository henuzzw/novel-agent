package com.novelagent.planning.application;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class OutlineGeneratorRegistry {
    private final Map<ModelProvider, OutlineGenerator> generators;

    public OutlineGeneratorRegistry(List<OutlineGenerator> generators) {
        Map<ModelProvider, OutlineGenerator> values = new EnumMap<>(ModelProvider.class);
        generators.forEach(generator -> {
            if (values.put(generator.provider(), generator) != null)
                throw new IllegalStateException("Duplicate outline generator: " + generator.provider());
        });
        this.generators = Map.copyOf(values);
    }

    public OutlineGenerator require(ModelProvider provider) {
        OutlineGenerator value = generators.get(provider);
        if (value == null) throw new IllegalArgumentException("不支持的模型提供方：" + provider);
        return value;
    }
}
