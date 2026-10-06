package com.novelagent.writing.domain;

import java.util.UUID;

public record ReaderExperienceManuscript(UUID id, long rowVersion, int chapterNumber, String title,
        boolean canon, boolean superseded) { }
