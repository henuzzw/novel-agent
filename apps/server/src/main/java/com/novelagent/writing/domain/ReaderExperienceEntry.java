package com.novelagent.writing.domain;

import java.util.List;

public record ReaderExperienceEntry(ReaderExperiencePlan plan, ReaderExperienceState state,
        boolean stale, List<Evidence> history) {
    public record Evidence(ReaderExperienceEvent event, boolean stale, String staleReason, boolean canon) { }
}
