package com.novelagent.writing.application;

import com.novelagent.writing.domain.ManuscriptContent;
import java.util.List;

public record GeneratedManuscript(ManuscriptContent content, List<String> changeSummary) {
    public GeneratedManuscript(ManuscriptContent content) {
        this(content, List.of());
    }
}
