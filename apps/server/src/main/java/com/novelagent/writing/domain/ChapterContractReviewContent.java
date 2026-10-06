package com.novelagent.writing.domain;

import java.util.List;

public record ChapterContractReviewContent(String summary, List<ReviewIssue> issues) {
}
