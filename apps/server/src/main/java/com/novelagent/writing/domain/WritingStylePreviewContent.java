package com.novelagent.writing.domain;

public record WritingStylePreviewContent(String title, String body) {
    public WritingStylePreviewContent {
        if (title == null || title.isBlank() || title.length() > 200) {
            throw new IllegalArgumentException("试写标题为空或超过 200 字符");
        }
        if (body == null || body.isBlank() || body.length() > 6000) {
            throw new IllegalArgumentException("试写正文为空或超过 6000 字符");
        }
    }
}
