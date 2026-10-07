package com.novelagent.writing.domain;

import com.novelagent.platform.support.Sha256;

public final class ReaderExperienceFingerprint {
    public static String of(String value) {
        return Sha256.ofUtf8(value);
    }
    private ReaderExperienceFingerprint() { }
}
