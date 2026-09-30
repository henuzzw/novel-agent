package com.novelagent.writing.domain;

public record FactProposal(String id, String factType, String subject, String predicate,
        String object, String evidence, Double confidence, TypedFactPayload payload,
        FactDecision decision) {

    public FactProposal(String id, String factType, String subject, String predicate,
            String object, String evidence, FactDecision decision) {
        this(id, factType, subject, predicate, object, evidence, null, null, decision);
    }
}
