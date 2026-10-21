package com.novelagent.writing.domain;

import static com.novelagent.platform.support.Text.isBlank;

public record WritingStylePreviewContent(String title, String body) {
    public WritingStylePreviewContent {
        if (isBlank(title) || title.length() > 200) {
            throw new IllegalArgumentException("试写标题为空或超过 200 字符");
        }
        if (isBlank(body) || body.length() > 6000) {
            throw new IllegalArgumentException("试写正文为空或超过 6000 字符");
        }
    }
}
