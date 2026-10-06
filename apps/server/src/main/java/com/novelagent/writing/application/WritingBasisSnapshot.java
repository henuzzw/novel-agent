package com.novelagent.writing.application;

public record WritingBasisSnapshot(String fingerprint) {
    public static WritingBasisSnapshot capture(WritingContextService.Context context) {
        return new WritingBasisSnapshot(context.boundaryFingerprint());
    }

    public void requireUnchanged(WritingContextService.Context current) {
        if (!fingerprint.equals(current.boundaryFingerprint())) {
            throw new IllegalStateException("生成期间大纲、圣经、创作策略或相邻章计划已变化，请刷新后重试");
        }
    }
}
