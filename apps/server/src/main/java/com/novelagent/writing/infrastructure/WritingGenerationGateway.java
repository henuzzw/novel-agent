package com.novelagent.writing.infrastructure;

import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.canon.application.EntityCatalogContext;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ChapterReviewContent;
import com.novelagent.writing.domain.ManuscriptContent;
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

    public WritingGenerationGateway(
            WritingPromptFactory prompts,
            WritingOutputSchemas schemas,
            WritingModelOutputParser parser,
            LocalWritingGenerator local,
            WritingModelRouter models) {
        this.prompts = prompts;
        this.schemas = schemas;
        this.parser = parser;
        this.local = local;
        this.models = models;
    }

    public ChapterContractContent contract(UUID projectId, StoryBibleContent bible, OutlineArc arc,
            ChapterPlan chapter, NovelMemoryContext memory, ModelProvider provider, String instruction) {
        if (provider == ModelProvider.LOCAL_TEMPLATE) {
            return local.contract(chapter);
        }
        String output = models.request(projectId, "CHAPTER_CONTRACT", provider,
                prompts.contract(projectId, bible, arc, chapter, memory, instruction), schemas.contract(),
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
}
