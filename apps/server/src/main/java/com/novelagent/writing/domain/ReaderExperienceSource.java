package com.novelagent.writing.domain;

import java.util.Objects;
import java.util.UUID;

public record ReaderExperienceSource(UUID id, UUID projectId, long rowVersion, int chapterNumber,
        ManuscriptStatus status, String body, UUID chapterCanonCommitId, UUID canonManuscriptId,
        Long canonVersion, boolean superseded) {
    public boolean canon() { return !superseded && id.equals(canonManuscriptId); }
    public String fingerprint() { return ReaderExperienceFingerprint.of(body == null ? "" : body); }

    @com.fasterxml.jackson.annotation.JsonProperty("fingerprint")
    public String getFingerprint() { return fingerprint(); }

    public void requireEvidence(UUID expectedProject, long expectedVersion, String evidence) {
        if (!projectId.equals(expectedProject)) throw new IllegalArgumentException("来源正文不属于当前项目");
        if (status != ManuscriptStatus.AUTHOR_ACCEPTED) throw new IllegalArgumentException("来源正文尚未 AUTHOR_ACCEPTED");
        if (rowVersion != expectedVersion) throw new IllegalArgumentException("来源正文版本已变化");
        if (superseded) throw new IllegalArgumentException("来源正文的正史已被替换");
        if (evidence == null || evidence.isBlank() || body == null || !body.contains(evidence)) {
            throw new IllegalArgumentException("证据必须是 body 中逐字存在的连续原文");
        }
    }

    public String staleReason(long sourceVersion, UUID canonBasis, boolean wasCanon, String evidence) {
        if (status != ManuscriptStatus.AUTHOR_ACCEPTED || rowVersion != sourceVersion || body == null || !body.contains(evidence)) return "来源正文已变化";
        if (superseded) return "来源正文的正史已被替换";
        if (!Objects.equals(canonBasis, chapterCanonCommitId)
                && !(canonBasis == null && !wasCanon && canon())) return "章节正史来源已变化";
        return null;
    }
}
