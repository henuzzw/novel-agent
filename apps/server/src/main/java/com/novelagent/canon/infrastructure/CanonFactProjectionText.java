package com.novelagent.canon.infrastructure;

import com.novelagent.writing.domain.FactProposal;

final class CanonFactProjectionText {
    private CanonFactProjectionText() {
    }

    static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    static String factSentence(FactProposal proposal) {
        return normalizedText(proposal.subject()) + " " + normalizedText(proposal.predicate())
                + " " + normalizedText(proposal.object());
    }

    static String normalizedText(String value) {
        if (value == null || value.isBlank()) {
            return "未命名";
        }
        return value.trim();
    }

    static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
