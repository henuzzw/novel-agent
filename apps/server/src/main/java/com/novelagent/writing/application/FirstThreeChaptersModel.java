package com.novelagent.writing.application;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.domain.FirstThreeChaptersBudget;
import com.novelagent.writing.domain.FirstThreeChaptersContent;
import com.novelagent.writing.domain.FirstThreeChaptersSource;

public interface FirstThreeChaptersModel {
    FirstThreeChaptersBudget budget(FirstThreeChaptersSource source, ModelProvider provider, String instruction);
    FirstThreeChaptersContent check(FirstThreeChaptersSource source, ModelProvider provider, String instruction);
}
