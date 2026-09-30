package com.novelagent.writing.domain;

import java.util.List;

public record ChapterReviewContent(String summary, List<ReviewIssue> issues, List<FactProposal> factProposals) {
}
