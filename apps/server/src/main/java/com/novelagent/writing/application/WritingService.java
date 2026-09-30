package com.novelagent.writing.application;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.canon.application.EntityCatalogService;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.memory.application.ContextBudgetPlanner;
import com.novelagent.memory.application.MemoryBudgetPlan;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.memory.application.NovelMemoryService;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.GenerationMode;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.writing.api.ChapterContractResponse;
import com.novelagent.writing.api.ChapterReviewResponse;
import com.novelagent.writing.api.GenerateWritingRequest;
import com.novelagent.writing.api.ManuscriptResponse;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ChapterContractVersion;
import com.novelagent.writing.domain.ChapterReviewContent;
import com.novelagent.writing.domain.ChapterReviewVersion;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.infrastructure.ChapterContractVersionRepository;
import com.novelagent.writing.infrastructure.ChapterReviewVersionRepository;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class WritingService {
    private final NovelProjectRepository projects;
    private final OutlineVersionRepository outlines;
    private final StoryBibleVersionRepository bibles;
    private final ChapterContractVersionRepository contracts;
    private final ManuscriptVersionRepository manuscripts;
    private final ChapterReviewVersionRepository reviews;
    private final WritingGenerationWorkflow workflow;
    private final CurrentActorProvider actorProvider;
    private final NovelMemoryService memory;
    private final ContextBudgetPlanner budgetPlanner;
    private final EntityCatalogService entityCatalog;
    private final CharacterNameService characterNames;

    public WritingService(NovelProjectRepository projects, OutlineVersionRepository outlines,
            StoryBibleVersionRepository bibles, ChapterContractVersionRepository contracts,
            ManuscriptVersionRepository manuscripts, ChapterReviewVersionRepository reviews,
            WritingGenerationWorkflow workflow, CurrentActorProvider actorProvider, NovelMemoryService memory,
            ContextBudgetPlanner budgetPlanner, EntityCatalogService entityCatalog,
            CharacterNameService characterNames) {
        this.projects = projects;
        this.outlines = outlines;
        this.bibles = bibles;
        this.contracts = contracts;
        this.manuscripts = manuscripts;
        this.reviews = reviews;
        this.workflow = workflow;
        this.actorProvider = actorProvider;
        this.memory = memory;
        this.budgetPlanner = budgetPlanner;
        this.entityCatalog = entityCatalog;
        this.characterNames = characterNames;
    }

    @Transactional(readOnly = true)
    public Optional<ChapterContractResponse> latestContract(UUID projectId, int chapterNumber) {
        requireOwnedProject(projectId);
        return contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .map(ChapterContractResponse::from);
    }

    public ChapterContractResponse generateContract(UUID projectId, int chapterNumber, GenerateWritingRequest request) {
        WritingContext context = context(projectId, chapterNumber);
        ModelProvider provider = request.provider() == null ? ModelProvider.LOCAL_TEMPLATE : request.provider();
        MemoryBudgetPlan budget = budgetPlanner.plan(AgentStage.CHAPTER_CONTRACT, provider,
                context.bible().getContent(), context.arc(), context.chapter(), request.instruction());
        NovelMemoryContext recalled = recall(AgentStage.CHAPTER_CONTRACT, context, request.instruction(), budget);
        ChapterContractContent generated = workflow.generateContract(projectId, context.bible().getContent(), context.arc(),
                context.chapter(), recalled, provider, normalize(request.instruction()));
        int version = contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .map(value -> value.getVersionNumber() + 1).orElse(1);
        ChapterContractVersion result = ChapterContractVersion.create(UUID.randomUUID(), projectId,
                context.outline().getId(), chapterNumber, version, provider.name(), normalize(request.instruction()), generated);
        return ChapterContractResponse.from(contracts.saveAndFlush(result));
    }

    @Transactional
    public ChapterContractResponse updateContract(UUID projectId, UUID id, long expected, ChapterContractContent content) {
        requireOwnedProject(projectId);
        ChapterContractVersion value = requireContract(projectId, id);
        check(value.getRowVersion(), expected);
        value.revise(content);
        return ChapterContractResponse.from(contracts.saveAndFlush(value));
    }

    @Transactional
    public ChapterContractResponse approveContract(UUID projectId, UUID id, long expected) {
        requireOwnedProject(projectId);
        ChapterContractVersion value = requireContract(projectId, id);
        check(value.getRowVersion(), expected);
        value.approve();
        return ChapterContractResponse.from(contracts.saveAndFlush(value));
    }

    @Transactional(readOnly = true)
    public Optional<ManuscriptResponse> latestManuscript(UUID projectId, int chapterNumber) {
        requireOwnedProject(projectId);
        return manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .map(this::manuscriptResponse);
    }

    public ManuscriptResponse generateManuscript(UUID projectId, int chapterNumber, GenerateWritingRequest request) {
        WritingContext context = context(projectId, chapterNumber);
        ChapterContractVersion contract = contracts
                .findFirstByProjectIdAndChapterNumberAndStatusOrderByVersionNumberDesc(
                        projectId, chapterNumber, ChapterContractStatus.APPROVED)
                .orElseThrow(() -> new IllegalArgumentException("请先生成并确认本章的章节合同"));
        if (!contract.getSourceOutlineVersionId().equals(context.outline().getId())) {
            throw new IllegalArgumentException("章节合同来自旧大纲，请按当前已发布大纲重新生成并确认");
        }
        ModelProvider provider = request.provider() == null ? ModelProvider.LOCAL_TEMPLATE : request.provider();
        Optional<ManuscriptVersion> previous = manuscripts
                .findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber);
        ManuscriptContent previousContent = request.mode() == GenerationMode.REGENERATE
                ? null
                : previous.map(ManuscriptVersion::getContent).orElse(null);
        MemoryBudgetPlan budget = budgetPlanner.plan(AgentStage.MANUSCRIPT, provider,
                context.bible().getContent(), context.arc(), context.chapter(), contract.getContent(),
                previousContent, request.instruction());
        NovelMemoryContext recalled = recall(AgentStage.MANUSCRIPT, context, request.instruction(), budget);
        GeneratedManuscript generated = workflow.generateManuscript(projectId, context.bible().getContent(), context.arc(),
                context.chapter(), contract.getContent(), recalled, previousContent, provider,
                normalize(request.instruction()));
        ManuscriptContent generatedContent = characterNames.tokenize(projectId, generated.content());
        int version = previous.map(value -> value.getVersionNumber() + 1).orElse(1);
        ManuscriptVersion result = ManuscriptVersion.create(UUID.randomUUID(), projectId, contract.getId(),
                chapterNumber, version, provider.name(), normalize(request.instruction()), generatedContent,
                generated.changeSummary());
        return manuscriptResponse(manuscripts.saveAndFlush(result));
    }

    @Transactional
    public ManuscriptResponse updateManuscript(UUID projectId, UUID id, long expected, ManuscriptContent content) {
        requireOwnedProject(projectId);
        ManuscriptVersion value = requireManuscript(projectId, id);
        check(value.getRowVersion(), expected);
        value.revise(characterNames.tokenize(projectId, content));
        return manuscriptResponse(manuscripts.saveAndFlush(value));
    }

    @Transactional
    public ManuscriptResponse acceptManuscript(UUID projectId, UUID id, long expected) {
        requireOwnedProject(projectId);
        ManuscriptVersion value = requireManuscript(projectId, id);
        check(value.getRowVersion(), expected);
        value.accept();
        return manuscriptResponse(manuscripts.saveAndFlush(value));
    }

    @Transactional(readOnly = true)
    public Optional<ChapterReviewResponse> latestReview(UUID projectId, int chapterNumber) {
        requireOwnedProject(projectId);
        return reviews.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .map(ChapterReviewResponse::from);
    }

    public ChapterReviewResponse generateReview(UUID projectId, int chapterNumber, GenerateWritingRequest request) {
        WritingContext context = context(projectId, chapterNumber);
        ManuscriptVersion manuscript = manuscripts.findFirstByProjectIdAndChapterNumberAndStatusOrderByVersionNumberDesc(
                projectId, chapterNumber, ManuscriptStatus.AUTHOR_ACCEPTED)
                .orElseThrow(() -> new IllegalArgumentException("请先由作者确认本章正文"));
        ChapterContractVersion contract = contracts.findByIdAndProjectId(manuscript.getSourceContractVersionId(), projectId)
                .orElseThrow(() -> new IllegalArgumentException("正文关联的章节合同不可用"));
        ModelProvider provider = request.provider() == null ? ModelProvider.LOCAL_TEMPLATE : request.provider();
        MemoryBudgetPlan budget = budgetPlanner.plan(AgentStage.CHAPTER_REVIEW, provider,
                context.bible().getContent(), contract.getContent(), manuscript.getContent(), request.instruction());
        NovelMemoryContext recalled = recall(AgentStage.CHAPTER_REVIEW, context, request.instruction(), budget);
        ChapterReviewContent generated = workflow.generateReview(projectId, context.bible().getContent(), contract.getContent(),
                manuscript.getContent(), recalled, entityCatalog.forReview(projectId, contract.getContent()),
                provider, normalize(request.instruction()));
        int version = reviews.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .map(value -> value.getVersionNumber() + 1).orElse(1);
        ChapterReviewVersion review = ChapterReviewVersion.create(UUID.randomUUID(), projectId, chapterNumber,
                manuscript.getId(), version, provider.name(), normalize(request.instruction()), generated);
        return ChapterReviewResponse.from(reviews.saveAndFlush(review));
    }

    @Transactional
    public ChapterReviewResponse updateReview(UUID projectId, UUID id, long expected, ChapterReviewContent content) {
        requireOwnedProject(projectId);
        ChapterReviewVersion value = requireReview(projectId, id);
        check(value.getRowVersion(), expected);
        value.revise(content);
        return ChapterReviewResponse.from(reviews.saveAndFlush(value));
    }

    @Transactional
    public ChapterReviewResponse approveReview(UUID projectId, UUID id, long expected) {
        requireOwnedProject(projectId);
        ChapterReviewVersion value = requireReview(projectId, id);
        check(value.getRowVersion(), expected);
        value.approve();
        return ChapterReviewResponse.from(reviews.saveAndFlush(value));
    }

    private WritingContext context(UUID projectId, int chapterNumber) {
        NovelProject project = requireOwnedProject(projectId);
        UUID outlineId = project.getCurrentOutlineVersionId();
        if (outlineId == null) {
            throw new IllegalArgumentException("请先发布分层大纲");
        }
        OutlineVersion outline = outlines.findByIdAndProjectId(outlineId, projectId)
                .filter(value -> value.getStatus() == OutlineStatus.PUBLISHED)
                .orElseThrow(() -> new IllegalArgumentException("项目当前大纲不可用"));
        StoryBibleVersion bible = bibles.findByIdAndProjectId(outline.getSourceBibleVersionId(), projectId)
                .orElseThrow(() -> new IllegalArgumentException("大纲关联的故事圣经不可用"));
        for (OutlineArc arc : outline.getContent().arcs()) {
            for (ChapterPlan chapter : arc.chapters()) {
                if (chapter.number() == chapterNumber) {
                    return new WritingContext(outline, bible, arc, chapter);
                }
            }
        }
        throw new IllegalArgumentException("当前大纲中不存在第 " + chapterNumber + " 章");
    }
    private NovelProject requireOwnedProject(UUID id) {
        return projects.findById(id).filter(value -> value.getOwnerId().equals(actorProvider.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(id));
    }
    private NovelMemoryContext recall(AgentStage stage, WritingContext context, String instruction,
            MemoryBudgetPlan budget) {
        NovelProject project = requireOwnedProject(context.outline().getProjectId());
        String query = context.chapter().title() + " " + context.chapter().pov() + " " + context.chapter().objective() + " "
                + context.chapter().coreEvent() + " " + (instruction == null ? "" : instruction);
        return memory.recall(stage, project.getId(), context.chapter().number(),
                project.getCurrentCanonVersion(), query, budget);
    }
    private ChapterContractVersion requireContract(UUID projectId, UUID id) {
        return contracts.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new WritingResourceNotFoundException("章节合同", id));
    }
    private ManuscriptVersion requireManuscript(UUID projectId, UUID id) {
        return manuscripts.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new WritingResourceNotFoundException("正文版本", id));
    }
    private ChapterReviewVersion requireReview(UUID projectId, UUID id) {
        return reviews.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new WritingResourceNotFoundException("章节审稿", id));
    }
    @Transactional(readOnly = true)
    public String exportManuscript(UUID projectId, UUID id) {
        requireOwnedProject(projectId);
        ManuscriptVersion value = requireManuscript(projectId, id);
        ManuscriptContent rendered = characterNames.render(projectId, value.getContent());
        return "# " + rendered.title() + "\n\n" + rendered.body();
    }

    private ManuscriptResponse manuscriptResponse(ManuscriptVersion value) {
        return ManuscriptResponse.from(value, characterNames.render(value.getProjectId(), value.getContent()));
    }
    private static void check(long actual, long expected) {
        if (actual != expected) {
            throw new ResourceVersionConflictException(expected, actual);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record WritingContext(
            OutlineVersion outline,
            StoryBibleVersion bible,
            OutlineArc arc,
            ChapterPlan chapter) {
    }
}
