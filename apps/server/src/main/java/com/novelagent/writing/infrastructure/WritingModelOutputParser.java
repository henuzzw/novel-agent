package com.novelagent.writing.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProviderException;
import com.novelagent.writing.application.GeneratedManuscript;
import com.novelagent.writing.domain.ChapterReviewContent;
import com.novelagent.writing.domain.FactDecision;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ReviewIssue;
import com.novelagent.writing.domain.QualityReviewContent;
import com.novelagent.writing.domain.WritingStyleProfile;
import com.novelagent.writing.domain.WritingStylePreviewContent;
import com.novelagent.writing.domain.WritingStyleRecommendationContent;
import com.novelagent.planning.domain.StoryBibleContent;
import org.springframework.stereotype.Component;

@Component
class WritingModelOutputParser {
    private final ObjectMapper mapper;

    WritingModelOutputParser(ObjectMapper mapper) {
        this.mapper = mapper;
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

    QualityReviewContent qualityReview(String output, ManuscriptContent manuscript) {
        QualityReviewContent content = read(output, QualityReviewContent.class);
        try {
            content.requireEvidenceIn(manuscript.body());
        } catch (IllegalArgumentException exception) {
            throw new ModelProviderException("质量报告引用的证据不在当前正文中", exception);
        }
        return content;
    }

    WritingStyleProfile writingStyle(String output) {
        return read(output, WritingStyleProfile.class);
    }

    WritingStyleProfile analyzedStyle(String output, String sample) {
        WritingStyleProfile profile = writingStyle(output);
        try {
            if (profile.basePresetId() != null || profile.craft() == null
                    || profile.craft().evidence().isEmpty() || !profile.craft().examples().isEmpty()) {
                throw new IllegalArgumentException("样本分析需要独立技法与原文依据，不能冒用预设或生成示例");
            }
            profile.craft().requireEvidenceIn(sample);
        } catch (IllegalArgumentException exception) {
            throw new ModelProviderException("样本风格分析不符合技法或证据约束", exception);
        }
        return profile;
    }

    WritingStylePreviewContent stylePreview(String output) {
        return read(output, WritingStylePreviewContent.class);
    }

    WritingStyleRecommendationContent styleRecommendation(String output, StoryBibleContent bible,
            java.util.List<WritingStyleProfile> presets) {
        WritingStyleRecommendationContent content = read(output, WritingStyleRecommendationContent.class);
        try {
            content.requireEvidenceIn(bible);
            if (content.recommendations().isEmpty() || content.recommendations().stream().anyMatch(item ->
                    presets.stream().noneMatch(profile -> profile.name().equals(item.presetName())))) {
                throw new IllegalArgumentException("模型需要返回一至三种已知预设");
            }
        } catch (IllegalArgumentException exception) {
            throw new ModelProviderException("风格推荐不符合预设或圣经证据约束", exception);
        }
        return content;
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
