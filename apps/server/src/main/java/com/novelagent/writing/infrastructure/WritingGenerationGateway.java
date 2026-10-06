package com.novelagent.writing.infrastructure;

import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.canon.application.EntityCatalogContext;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ChapterContractReviewContent;
import com.novelagent.writing.domain.ChapterReviewContent;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.QualityReviewContent;
import com.novelagent.writing.domain.WritingStyleProfile;
import com.novelagent.writing.domain.WritingStylePreviewContent;
import com.novelagent.writing.domain.WritingStyleRecommendationContent;
import com.novelagent.writing.domain.StylePreviewSource;
import com.novelagent.writing.application.GeneratedManuscript;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class WritingGenerationGateway {
    private final WritingPromptFactory prompts;
    private final WritingOutputSchemas schemas;
    private final WritingModelOutputParser parser;
    private final LocalWritingGenerator local;
    private final WritingModelRouter models;
    private final LocalQualityReviewer qualityRules;
    private final LocalStyleAnalyzer styleMetrics;

    public WritingGenerationGateway(
            WritingPromptFactory prompts,
            WritingOutputSchemas schemas,
            WritingModelOutputParser parser,
            LocalWritingGenerator local,
            WritingModelRouter models,
            LocalQualityReviewer qualityRules,
            LocalStyleAnalyzer styleMetrics) {
        this.prompts = prompts;
        this.schemas = schemas;
        this.parser = parser;
        this.local = local;
        this.models = models;
        this.qualityRules = qualityRules;
        this.styleMetrics = styleMetrics;
    }

    public ChapterContractContent contract(UUID projectId, StoryBibleContent bible, OutlineArc arc,
            ChapterPlan chapter, NovelMemoryContext memory, ChapterContractContent previousContract,
            ModelProvider provider, String instruction) {
        if (provider == ModelProvider.LOCAL_TEMPLATE) {
            return local.contract(chapter, previousContract, instruction);
        }
        String output = models.request(projectId, "CHAPTER_CONTRACT", provider,
                prompts.contract(projectId, bible, arc, chapter, memory, previousContract, instruction), schemas.contract(),
                "chapter_contract", 3000);
        return parser.contract(output);
    }

    public GeneratedManuscript manuscript(UUID projectId, StoryBibleContent bible, OutlineArc arc,
            ChapterPlan chapter, ChapterContractContent contract, NovelMemoryContext memory,
            ManuscriptContent previousManuscript, ModelProvider provider, String instruction) {
        if (provider == ModelProvider.LOCAL_TEMPLATE) {
            return local.manuscript(chapter, contract, previousManuscript, instruction);
        }
        String output = models.request(projectId, "MANUSCRIPT", provider,
                prompts.manuscript(projectId, bible, arc, chapter, contract, memory, previousManuscript, instruction),
                schemas.manuscript(),
                "manuscript", Math.max(5000, contract.suggestedMaxWords() * 2));
        return parser.manuscript(output);
    }

    public ChapterContractReviewContent contractReview(UUID projectId, StoryBibleContent bible, OutlineArc arc,
            ChapterPlan chapter, ChapterContractContent contract, NovelMemoryContext memory,
            ModelProvider provider, String instruction) {
        if (provider == ModelProvider.LOCAL_TEMPLATE) {
            return new ChapterContractReviewContent("本地模板未发现明确的合同冲突，请由作者复核。", java.util.List.of());
        }
        String output = models.request(projectId, "CHAPTER_CONTRACT_REVIEW", provider,
                prompts.contractReview(projectId, bible, arc, chapter, contract, memory, instruction),
                schemas.contractReview(), "chapter_contract_review", 2500);
        return parser.contractReview(output);
    }

    public ChapterReviewContent review(UUID projectId, StoryBibleContent bible,
            ChapterContractContent contract, ManuscriptContent manuscript, NovelMemoryContext memory,
            EntityCatalogContext entityCatalog, ModelProvider provider, String instruction) {
        if (provider == ModelProvider.LOCAL_TEMPLATE) {
            return local.review(contract, manuscript);
        }
        String output = models.request(projectId, "CHAPTER_REVIEW", provider,
                prompts.review(projectId, bible, contract, manuscript, memory, entityCatalog, instruction), schemas.review(),
                "chapter_review", 5000);
        return parser.review(output);
    }

    public QualityReviewContent qualityReview(UUID projectId, StoryBibleContent bible,
            ChapterContractContent contract, ManuscriptContent manuscript, NovelMemoryContext memory,
            ModelProvider provider, String instruction) {
        if (provider == ModelProvider.LOCAL_TEMPLATE) return qualityRules.review(manuscript);
        String output = models.request(projectId, "QUALITY_REVIEW", provider,
                prompts.qualityReview(projectId, bible, contract, manuscript, memory, instruction),
                schemas.qualityReview(), "quality_review", 5000);
        return parser.qualityReview(output, manuscript);
    }

    public WritingStyleProfile analyzeStyle(UUID projectId, String sample, ModelProvider provider) {
        if (provider == ModelProvider.LOCAL_TEMPLATE) return styleMetrics.analyze(sample);
        String output = models.request(projectId, "STYLE_ANALYSIS", provider, prompts.styleAnalysis(sample),
                schemas.writingStyle(), "writing_style_v2", 6000);
        return parser.analyzedStyle(output, sample);
    }

    public WritingStyleRecommendationContent recommendStyle(UUID projectId, StoryBibleContent bible,
            ModelProvider provider, String instruction) {
        if (provider == ModelProvider.LOCAL_TEMPLATE) {
            return new WritingStyleRecommendationContent("本地模板不判断故事与文风的语义适配，请选择 Codex 或 DeepSeek 分析。",
                    java.util.List.of());
        }
        var presets = prompts.stylePresets(projectId);
        if (presets.isEmpty()) throw new IllegalArgumentException("尚无可用风格预设");
        String output = models.request(projectId, "STYLE_RECOMMENDATION", provider,
                prompts.styleRecommendation(projectId, bible, instruction, presets), schemas.styleRecommendation(presets),
                "writing_style_recommendation", 4000);
        return parser.styleRecommendation(output, bible, presets);
    }

    public WritingStylePreviewContent stylePreview(UUID projectId, StoryBibleContent bible, OutlineArc arc,
            ChapterPlan chapter, WritingStyleProfile profile, ModelProvider provider, int targetWords,
            String instruction) {
        if (provider == ModelProvider.LOCAL_TEMPLATE) {
            return new WritingStylePreviewContent(chapter.title(),
                    "【本地模板，仅验证试写流程，不代表实际风格效果】\n\n第一章：" + chapter.title()
                            + "\n视角：" + chapter.pov() + "\n场景目标：" + chapter.objective()
                            + "\n核心事件：" + chapter.coreEvent() + "\n试写风格：" + profile.name());
        }
        String output = models.request(projectId, "STYLE_PREVIEW", provider,
                prompts.stylePreview(projectId, bible, arc, chapter, profile, targetWords, instruction),
                schemas.stylePreview(), "writing_style_preview", targetWords * 3 + 1000);
        return parser.stylePreview(output);
    }

    public QualityReviewContent reviewStylePreview(UUID projectId, StoryBibleContent bible, OutlineArc arc,
            ChapterPlan chapter, StylePreviewSource source) {
        var manuscript = new ManuscriptContent(source.content().title(), source.content().body(), "试写样例", java.util.List.of());
        var provider = ModelProvider.valueOf(source.provider());
        if (provider == ModelProvider.LOCAL_TEMPLATE) return qualityRules.review(manuscript);
        String output = models.request(projectId, "STYLE_PREVIEW_REVIEW", provider,
                prompts.reviewStylePreview(projectId, bible, arc, chapter, source), schemas.qualityReview(), "style_preview_review", 5000);
        return parser.qualityReview(output, manuscript);
    }

    public WritingStylePreviewContent reviseStylePreview(UUID projectId, StoryBibleContent bible, OutlineArc arc,
            ChapterPlan chapter, StylePreviewSource source, ModelProvider provider, String feedback) {
        if (provider == ModelProvider.LOCAL_TEMPLATE) throw new IllegalArgumentException("本地模板不能执行语义修订");
        String output = models.request(projectId, "STYLE_PREVIEW_REVISION", provider,
                prompts.reviseStylePreview(projectId, bible, arc, chapter, source, feedback), schemas.stylePreview(),
                "style_preview_revision", source.targetWords() * 3 + 1000);
        return parser.stylePreview(output);
    }
}
