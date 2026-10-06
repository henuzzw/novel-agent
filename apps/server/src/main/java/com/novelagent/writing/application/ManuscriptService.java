package com.novelagent.writing.application;

import static com.novelagent.writing.application.WritingChecks.check;
import static com.novelagent.writing.application.WritingChecks.normalize;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.memory.application.ContextBudgetPlanner;
import com.novelagent.memory.application.MemoryBudgetPlan;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.planning.application.GenerationMode;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.api.GenerateWritingRequest;
import com.novelagent.writing.api.ManuscriptResponse;
import com.novelagent.writing.api.ManuscriptVersionSummaryResponse;
import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ChapterContractVersion;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.infrastructure.ChapterContractVersionRepository;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManuscriptService {
    private final WritingContextService contexts;
    private final ChapterContractVersionRepository contracts;
    private final ManuscriptVersionRepository manuscripts;
    private final WritingGenerationWorkflow workflow;
    private final ContextBudgetPlanner budgetPlanner;
    private final CharacterNameService characterNames;
    private final WritingStyleService styles;

    public ManuscriptService(WritingContextService contexts, ChapterContractVersionRepository contracts,
            ManuscriptVersionRepository manuscripts, WritingGenerationWorkflow workflow,
            ContextBudgetPlanner budgetPlanner, CharacterNameService characterNames, WritingStyleService styles) {
        this.contexts = contexts;
        this.contracts = contracts;
        this.manuscripts = manuscripts;
        this.workflow = workflow;
        this.budgetPlanner = budgetPlanner;
        this.characterNames = characterNames;
        this.styles = styles;
    }

    @Transactional(readOnly = true)
    public Optional<ManuscriptResponse> latestManuscript(UUID projectId, int chapterNumber) {
        contexts.requireOwnedProject(projectId);
        return manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .map(this::manuscriptResponse);
    }

    @Transactional(readOnly = true)
    public List<ManuscriptVersionSummaryResponse> manuscriptVersions(UUID projectId, int chapterNumber) {
        contexts.requireOwnedProject(projectId);
        return manuscripts.findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber)
                .stream().map(value -> ManuscriptVersionSummaryResponse.from(value,
                        characterNames.render(projectId, value.getContent().title()))).toList();
    }

    @Transactional(readOnly = true)
    public ManuscriptResponse manuscriptVersion(UUID projectId, int chapterNumber, UUID id) {
        contexts.requireOwnedProject(projectId);
        return manuscriptResponse(manuscripts.findByIdAndProjectIdAndChapterNumber(id, projectId, chapterNumber)
                .orElseThrow(() -> new WritingResourceNotFoundException("正文版本", id)));
    }

    public ManuscriptResponse generateManuscript(UUID projectId, int chapterNumber, GenerateWritingRequest request) {
        return manuscriptResponse(manuscripts.saveAndFlush(prepareManuscript(projectId, chapterNumber, request)));
    }

    ManuscriptVersion prepareManuscript(UUID projectId, int chapterNumber, GenerateWritingRequest request) {
        WritingContextService.Context context = contexts.context(projectId, chapterNumber);
        WritingBasisSnapshot basis = WritingBasisSnapshot.capture(context);
        ChapterContractVersion contract = contracts
                .findFirstByProjectIdAndChapterNumberAndStatusOrderByVersionNumberDesc(
                        projectId, chapterNumber, ChapterContractStatus.APPROVED)
                .orElseThrow(() -> new IllegalArgumentException("请先生成并确认本章的章节合同"));
        if (!contract.getSourceOutlineVersionId().equals(context.outline().getId())) {
            throw new IllegalArgumentException("章节合同来自旧大纲，请按当前已发布大纲重新生成并确认");
        }
        long contractRowVersion = contract.getRowVersion();
        ChapterContractContentSnapshot contractSnapshot = new ChapterContractContentSnapshot(contract.getId(),
                contractRowVersion, contract.getContent());
        ModelProvider provider = request.provider() == null ? ModelProvider.LOCAL_TEMPLATE : request.provider();
        Optional<ManuscriptVersion> latest = manuscripts
                .findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber);
        if (request.mode() == GenerationMode.REGENERATE && request.baseManuscriptVersionId() != null) {
            throw new IllegalArgumentException("重新创作时不能指定基准正文版本");
        }
        ManuscriptVersion base = request.mode() == GenerationMode.REGENERATE ? null
                : request.baseManuscriptVersionId() == null ? latest.orElse(null)
                : manuscripts.findByIdAndProjectIdAndChapterNumber(
                        request.baseManuscriptVersionId(), projectId, chapterNumber)
                        .orElseThrow(() -> new WritingResourceNotFoundException(
                                "正文版本", request.baseManuscriptVersionId()));
        ManuscriptContent previousContent = base == null ? null : base.getContent();
        String styleContext = styles.promptContext(projectId);
        MemoryBudgetPlan budget = budgetPlanner.plan(AgentStage.MANUSCRIPT, provider,
                context.budgetInputs(context.bible().getContent(), context.arc(), context.chapter(), contract.getContent(),
                        previousContent, request.instruction(), styleContext));
        NovelMemoryContext recalled = contexts.recall(AgentStage.MANUSCRIPT, context, request.instruction(), budget);
        GeneratedManuscript generated = workflow.generateManuscript(projectId, context.bible().getContent(), context.arc(),
                context.chapter(), contract.getContent(), recalled, previousContent, provider,
                context.instructionWithPreparation(normalize(request.instruction())));
        basis.requireUnchanged(contexts.context(projectId, chapterNumber));
        ChapterContractVersion currentContract = contracts
                .findFirstByProjectIdAndChapterNumberAndStatusOrderByVersionNumberDesc(
                        projectId, chapterNumber, ChapterContractStatus.APPROVED).orElseThrow();
        if (!contractSnapshot.matches(currentContract)
                || !java.util.Objects.equals(styleContext, styles.promptContext(projectId))) {
            throw new IllegalStateException("生成期间章节合同或写作风格已变化，请刷新后重试");
        }
        if (base != null) {
            ManuscriptVersion currentBase = manuscripts.findByIdAndProjectIdAndChapterNumber(
                    base.getId(), projectId, chapterNumber).orElseThrow();
            if (!java.util.Objects.equals(previousContent, currentBase.getContent())) {
                throw new IllegalStateException("生成期间基准正文已变化，请刷新后重试");
            }
        }
        ManuscriptContent generatedContent = characterNames.tokenize(projectId, generated.content());
        int version = latest.map(value -> value.getVersionNumber() + 1).orElse(1);
        return ManuscriptVersion.create(UUID.randomUUID(), projectId, contract.getId(),
                chapterNumber, version, provider.name(), normalize(request.instruction()),
                base == null ? null : base.getId(), generatedContent,
                generated.changeSummary());
    }

    ManuscriptVersion prepareQualityRevision(UUID projectId, int chapterNumber, UUID sourceId,
            long sourceVersion, ModelProvider provider, String feedback) {
        contexts.requireOwnedProject(projectId);
        ManuscriptVersion source = requireManuscript(projectId, sourceId);
        check(source.getRowVersion(), sourceVersion);
        if (source.getChapterNumber() != chapterNumber || !manuscripts
                .findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapterNumber).orElseThrow()
                .getId().equals(sourceId)) throw new IllegalStateException("请从当前正文进行润色");
        if (provider == null || provider == ModelProvider.LOCAL_TEMPLATE) {
            return ManuscriptVersion.create(UUID.randomUUID(), projectId, source.getSourceContractVersionId(),
                    chapterNumber, source.getVersionNumber() + 1, ModelProvider.LOCAL_TEMPLATE.name(), feedback,
                    sourceId, source.getContent(), List.of("本地模板仅创建版本流程候选，保留原稿，未执行语义润色。"));
        }
        return prepareManuscript(projectId, chapterNumber, new GenerateWritingRequest(provider, feedback,
                GenerationMode.REVISE, sourceId, null));
    }

    @Transactional
    public ManuscriptResponse updateManuscript(UUID projectId, UUID id, long expected, ManuscriptContent content) {
        contexts.requireOwnedProject(projectId);
        ManuscriptVersion value = requireManuscript(projectId, id);
        check(value.getRowVersion(), expected);
        value.revise(characterNames.tokenize(projectId, content));
        return manuscriptResponse(manuscripts.saveAndFlush(value));
    }

    @Transactional
    public ManuscriptResponse createManuscriptRevision(UUID projectId, int chapterNumber, UUID sourceId, long expected) {
        contexts.requireOwnedProject(projectId);
        ManuscriptVersion source = manuscripts.findByIdAndProjectIdAndChapterNumber(sourceId, projectId, chapterNumber)
                .orElseThrow(() -> new WritingResourceNotFoundException("正文版本", sourceId));
        check(source.getRowVersion(), expected);
        if (source.getStatus() != ManuscriptStatus.AUTHOR_ACCEPTED) {
            throw new IllegalArgumentException("只有作者已确认的正文才能创建人工修订草稿");
        }
        ManuscriptVersion latest = manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(
                projectId, chapterNumber).orElseThrow();
        if (!latest.getId().equals(sourceId)) {
            throw new IllegalStateException("当前正文已有更新，请刷新后再创建修订草稿");
        }
        ManuscriptVersion revision = ManuscriptVersion.create(UUID.randomUUID(), projectId,
                source.getSourceContractVersionId(), chapterNumber, latest.getVersionNumber() + 1,
                "AUTHOR_EDIT", null, sourceId, source.getContent(), List.of());
        return manuscriptResponse(manuscripts.saveAndFlush(revision));
    }

    @Transactional
    public ManuscriptResponse acceptManuscript(UUID projectId, UUID id, long expected) {
        contexts.requireOwnedProject(projectId);
        ManuscriptVersion value = requireManuscript(projectId, id);
        check(value.getRowVersion(), expected);
        value.accept();
        return manuscriptResponse(manuscripts.saveAndFlush(value));
    }

    private ManuscriptVersion requireManuscript(UUID projectId, UUID id) {
        return manuscripts.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new WritingResourceNotFoundException("正文版本", id));
    }
    @Transactional(readOnly = true)
    public String exportManuscript(UUID projectId, UUID id) {
        contexts.requireOwnedProject(projectId);
        ManuscriptVersion value = requireManuscript(projectId, id);
        ManuscriptContent rendered = characterNames.render(projectId, value.getContent());
        return "# " + rendered.title() + "\n\n" + rendered.body();
    }

    private ManuscriptResponse manuscriptResponse(ManuscriptVersion value) {
        return ManuscriptResponse.from(value, renderContent(value));
    }
    ManuscriptContent renderContent(ManuscriptVersion value) {
        return characterNames.render(value.getProjectId(), value.getContent());
    }

    private record ChapterContractContentSnapshot(UUID id, long rowVersion,
            com.novelagent.writing.domain.ChapterContractContent content) {
        boolean matches(ChapterContractVersion value) {
            return id.equals(value.getId()) && rowVersion == value.getRowVersion()
                    && java.util.Objects.equals(content, value.getContent());
        }
    }
}
