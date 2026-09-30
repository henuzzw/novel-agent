package com.novelagent.planning.application;

import java.util.UUID;

public class OutlineVersionNotFoundException extends RuntimeException {
    public OutlineVersionNotFoundException(UUID id) { super("Outline version not found: " + id); }
}
