package com.novelagent.writing.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProviderException;
import com.novelagent.writing.application.GeneratedManuscript;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ChapterReviewContent;
import com.novelagent.writing.domain.FactDecision;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ReviewIssue;
import org.springframework.stereotype.Component;

@Component
class WritingModelOutputParser {
    private final ObjectMapper mapper;

    WritingModelOutputParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    ChapterContractContent contract(String output) {
        return read(output, ChapterContractContent.class);
    }

    GeneratedManuscript manuscript(String output) {
        GeneratedManuscript generated = read(output, GeneratedManuscript.class);
        return new GeneratedManuscript(generated.content(), generated.changeSummary() == null
                ? java.util.List.of()
                : generated.changeSummary().stream().filter(value -> value != null && !value.isBlank())
                        .map(String::trim).toList());
    }

    ChapterReviewContent review(String output) {
        ChapterReviewContent content = read(output, ChapterReviewContent.class);
        return new ChapterReviewContent(
                content.summary(),
                content.issues().stream()
                        .map(issue -> new ReviewIssue(issue.id(), issue.severity(), issue.category(),
                                issue.description(), issue.evidence(), issue.suggestion(), false))
                        .toList(),
                content.factProposals().stream()
                        .map(this::normalizeFact)
                        .toList());
    }

    private FactProposal normalizeFact(FactProposal fact) {
        TypedFactProposalValidator.validate(fact);
        return new FactProposal(fact.id(), fact.factType(), fact.subject(), fact.predicate(),
                fact.object(), fact.evidence(), fact.confidence(), fact.payload(), FactDecision.PENDING);
    }

    private <T> T read(String value, Class<T> type) {
        try {
            return mapper.readValue(value, type);
        } catch (JsonProcessingException exception) {
            throw new ModelProviderException("模型返回内容不符合写作结构约束", exception);
        }
    }
}
