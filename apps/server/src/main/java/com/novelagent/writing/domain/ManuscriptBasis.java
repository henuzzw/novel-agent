package com.novelagent.writing.domain;

import java.util.UUID;

/** Frozen outline-derived writing basis, not a generated or author-approved contract. */
public record ManuscriptBasis(UUID outlineId, String fingerprint, ChapterContractContent plan) {
    public ManuscriptBasis {
        java.util.Objects.requireNonNull(outlineId);
        java.util.Objects.requireNonNull(fingerprint);
        java.util.Objects.requireNonNull(plan);
    }
}
