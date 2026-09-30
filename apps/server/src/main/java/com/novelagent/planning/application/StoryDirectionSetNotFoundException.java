package com.novelagent.planning.application;

import java.util.UUID;

public class StoryDirectionSetNotFoundException extends RuntimeException {

    public StoryDirectionSetNotFoundException(UUID setId) {
        super("Story direction set not found: " + setId);
    }
}
