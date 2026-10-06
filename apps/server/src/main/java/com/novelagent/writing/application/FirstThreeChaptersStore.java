package com.novelagent.writing.application;

import com.novelagent.writing.domain.FirstThreeChaptersBudget;
import com.novelagent.writing.domain.FirstThreeChaptersContent;
import com.novelagent.writing.domain.FirstThreeChaptersReport;
import com.novelagent.writing.domain.FirstThreeChaptersSource;
import java.util.List;
import java.util.UUID;

public interface FirstThreeChaptersStore {
    FirstThreeChaptersSource snapshot(UUID projectId, List<UUID> manuscriptIds);
    List<FirstThreeChaptersReport> latest(UUID projectId, String fingerprint);
    FirstThreeChaptersReport save(FirstThreeChaptersSource source, List<UUID> selection, String provider,
            String instruction, FirstThreeChaptersContent content, FirstThreeChaptersBudget budget);
}
