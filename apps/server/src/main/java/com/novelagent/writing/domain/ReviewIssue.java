package com.novelagent.writing.domain;

public record ReviewIssue(String id, String severity, String category, String description,
        String evidence, String suggestion, boolean resolved) {
}
