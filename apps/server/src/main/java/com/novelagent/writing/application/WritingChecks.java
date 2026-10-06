package com.novelagent.writing.application;

import com.novelagent.project.application.ResourceVersionConflictException;

final class WritingChecks {
    private WritingChecks() { }

    static void check(long actual, long expected) {
        if (actual != expected) throw new ResourceVersionConflictException(expected, actual);
    }

    static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
