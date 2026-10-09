package com.novelagent.writing.infrastructure;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class WritingCraftConfiguration {
    private final boolean enabled;

    public WritingCraftConfiguration(@Value("${novel.writing.craft-rules-enabled:true}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }
    public String manuscript() { return enabled ? WritingCraftRules.manuscript() : ""; }
    public String qualityReview() { return enabled ? WritingCraftRules.qualityReview() : ""; }
    public String preview() { return enabled ? WritingCraftRules.preview() : ""; }
}
