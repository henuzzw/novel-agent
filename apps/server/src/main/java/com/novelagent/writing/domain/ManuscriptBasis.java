package com.novelagent.writing.domain;

import java.util.Objects;
import java.util.UUID;

/** Frozen outline-derived writing basis, not a generated or author-approved contract. */
public record ManuscriptBasis(UUID outlineId, String fingerprint, ChapterContractContent plan) {
    public ManuscriptBasis {
        Objects.requireNonNull(outlineId);
        Objects.requireNonNull(fingerprint);
        Objects.requireNonNull(plan);
    }
}
