package com.novelagent.planning.application;

import java.util.UUID;

public class StoryBibleVersionNotFoundException extends RuntimeException {
    public StoryBibleVersionNotFoundException(UUID id) { super("Story bible version not found: " + id); }
}
