package com.novelagent.writing.domain;

import java.util.UUID;

public record ReaderExperienceSubmission(UUID requestId, Long expectedVersion, ReaderExperienceState state,
        UUID manuscriptId, Long manuscriptRowVersion, String sourceFingerprint, String evidence, String authorNote, boolean authorConfirmed) {
    public void validate() {
        if (!authorConfirmed) throw new IllegalArgumentException("实际进展必须由作者明确确认提交");
        if (requestId == null || expectedVersion == null || expectedVersion < 0 || manuscriptId == null
                || manuscriptRowVersion == null || manuscriptRowVersion < 0 || state == null) {
            throw new IllegalArgumentException("提交缺少有效版本、状态或来源正文");
        }
        if (evidence == null || evidence.isBlank() || evidence.length() > 6000) throw new IllegalArgumentException("正文证据为空或过长");
        if (sourceFingerprint == null || !sourceFingerprint.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("缺少有效的正文显示依据指纹");
        if (authorNote != null && authorNote.length() > 4000) throw new IllegalArgumentException("作者说明过长");
    }
}
