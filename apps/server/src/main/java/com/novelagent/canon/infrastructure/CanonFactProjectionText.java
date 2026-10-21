package com.novelagent.canon.infrastructure;

import static com.novelagent.platform.support.Text.isBlank;

import com.novelagent.writing.domain.FactProposal;

final class CanonFactProjectionText {
    private CanonFactProjectionText() {
    }

    static String factSentence(FactProposal proposal) {
        return normalizedText(proposal.subject()) + " " + normalizedText(proposal.predicate())
                + " " + normalizedText(proposal.object());
    }

    static String normalizedText(String value) {
        if (isBlank(value)) {
            return "未命名";
        }
        return value.trim();
    }

    static String truncate(String value, int maxLength) {
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
