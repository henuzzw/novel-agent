package com.novelagent.planning.application;

import com.novelagent.planning.domain.OutlineContent;
import java.util.List;

public record GeneratedOutline(String generatorType, OutlineContent content, List<String> changeSummary) {
    public GeneratedOutline(String generatorType, OutlineContent content) {
        this(generatorType, content, List.of());
    }
}
