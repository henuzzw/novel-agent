package com.novelagent.agent.application;

import static com.novelagent.support.TestDatabase.env;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.api.dto.CreateAutomationRunRequest;
import com.novelagent.agent.domain.AutomationStatus;
import com.novelagent.canon.api.dto.CommitCanonRequest;
import com.novelagent.canon.application.CanonCommitService;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.canon.application.ProjectionStatusService;
import com.novelagent.canon.infrastructure.OutboxPublisher;
import com.novelagent.ingest.api.dto.PrepareImportedStoryRequest;
import com.novelagent.ingest.api.dto.WorkImportResponse;
import com.novelagent.ingest.application.ImportAnalysisRunner;
import com.novelagent.ingest.application.ImportAnalysisStore;
import com.novelagent.ingest.application.ImportedStoryBlueprintService;
import com.novelagent.ingest.application.WorkImportService;
import com.novelagent.ingest.domain.ImportAnalysis;
import com.novelagent.ingest.domain.ImportPlanningMode;
import com.novelagent.memory.infrastructure.SemanticEmbeddingBackfill;
import com.novelagent.planning.application.GenerationMode;
import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.planning.application.OutlineService;
import com.novelagent.planning.application.PlanningBatchRunner;
import com.novelagent.planning.application.PlanningBatchService;
import com.novelagent.planning.application.PlanningCheckpointException;
import com.novelagent.planning.application.PlanningCheckpointService;
import com.novelagent.planning.application.PlanningMaterialSyncService;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.CharacterBlueprintFixtures;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.PlanningBatch;
import com.novelagent.planning.domain.PlanningCheckpoint;
import com.novelagent.planning.domain.PlanningCheckpointResult;
import com.novelagent.planning.domain.ReaderExperienceSeed;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.modelaccess.infrastructure.CodexAppServerException;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.modelaccess.application.StructuredModelGateway;
import com.novelagent.project.application.CreativeStrategyService;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.CreativeIntent;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.GlobalModelSettings;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.CreativeIntentRepository;
import com.novelagent.project.infrastructure.GlobalModelSettingsRepository;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.support.DatabaseTest;
import com.novelagent.support.TestDatabase;
import com.novelagent.writing.api.dto.CheckStylePreviewRequest;
import com.novelagent.writing.api.dto.GenerateStylePreviewRequest;
import com.novelagent.writing.api.dto.GenerateWritingRequest;
import com.novelagent.writing.api.dto.RecommendWritingStyleRequest;
import com.novelagent.writing.api.dto.ReturnReviewRequest;
import com.novelagent.writing.api.dto.ReviseQualityRequest;
import com.novelagent.writing.api.dto.ReviseStylePreviewRequest;
import com.novelagent.writing.application.FirstThreeChaptersService;
import com.novelagent.writing.application.GeneratedManuscript;
import com.novelagent.writing.application.QualityReviewService;
import com.novelagent.writing.application.QualityReviewStore;
import com.novelagent.writing.application.ReaderExperienceService;
import com.novelagent.writing.application.StylePreviewEditingService;
import com.novelagent.writing.application.StylePreviewReviewStore;
import com.novelagent.writing.application.WritingContextService;
import com.novelagent.writing.application.WritingResourceNotFoundException;
import com.novelagent.writing.application.WritingService;
import com.novelagent.writing.application.WritingStyleAnalysisService;
import com.novelagent.writing.application.WritingStylePresets;
import com.novelagent.writing.application.WritingStylePreviewService;
import com.novelagent.writing.application.WritingStyleRecommendationService;
import com.novelagent.writing.application.WritingStyleService;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ChapterContractVersion;
import com.novelagent.writing.domain.ChapterReviewContent;
import com.novelagent.writing.domain.FactDecision;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.FirstThreeChaptersContent;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.domain.QualityDimension;
import com.novelagent.writing.domain.QualityReviewContent;
import com.novelagent.writing.domain.QualityScore;
import com.novelagent.writing.domain.ReaderExperiencePlan;
import com.novelagent.writing.domain.ReaderExperiencePlanInput;
import com.novelagent.writing.domain.ReaderExperienceState;
import com.novelagent.writing.domain.ReaderExperienceSubmission;
import com.novelagent.writing.domain.ReviewIssue;
import com.novelagent.writing.domain.ReviewStatus;
import com.novelagent.writing.domain.TypedFactPayload;
import com.novelagent.writing.domain.WritingStylePreviewContent;
import com.novelagent.writing.domain.WritingStyleProfile;
import com.novelagent.writing.domain.WritingStyleRecommendationContent;
import com.novelagent.writing.infrastructure.ChapterContractVersionRepository;
import com.novelagent.writing.infrastructure.ChapterReviewVersionRepository;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import com.novelagent.writing.infrastructure.WritingGenerationGateway;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, properties = {
        "spring.jpa.hibernate.ddl-auto=validate", "spring.kafka.listener.auto-startup=false",
        "spring.kafka.admin.auto-create=false", "logging.file.name=target/automation-test.log"})
@DatabaseTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AutomationPersistenceTest {
    private static final String SCHEMA = "automation_test_" + UUID.randomUUID().toString().replace("-", "");
    @Autowired private AutomationRunStore store;
    @Autowired private NovelProjectRepository projects;
    @Autowired private StoryBibleVersionRepository bibles;
    @Autowired private OutlineVersionRepository outlines;
    @Autowired private CurrentActorProvider actor;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private GlobalModelSettingsRepository modelSettings;
    @Autowired private AutomationService automation;
    @Autowired private WritingService writing;
    @Autowired private CanonCommitService canon;
    @Autowired private WritingStyleService styles;
    @Autowired private WritingStyleAnalysisService styleAnalysis;
    @Autowired private WritingStylePreviewService stylePreviews;
    @Autowired private WritingStyleRecommendationService styleRecommendations;
    @Autowired private QualityReviewService quality;
    @Autowired private QualityReviewStore qualityStore;
    @Autowired private StylePreviewEditingService previewEditing;
    @Autowired private StylePreviewReviewStore previewReviewStore;
    @Autowired private AgentRunQueryService runQueries;
    @Autowired private AgentRunRecorder runRecorder;
    @Autowired private CreativeStrategyService strategies;
    @Autowired private FirstThreeChaptersService openingReviews;
    @Autowired private ReaderExperienceService readerExperiences;
    @Autowired private PlanningCheckpointService checkpoints;
    @Autowired private PlanningBatchService planningBatches;
    @Autowired private PlanningBatchRunner planningBatchRunner;
    @Autowired private CreativeIntentRepository creativeIntents;
    @Autowired private ObjectMapper json;
    @Autowired private PlanningMaterialSyncService planningMaterials;
    @Autowired private OutlineService outlineService;
    @Autowired private CharacterProfileService characterProfiles;
    @Autowired private CharacterNameService characterNames;
    @Autowired private WritingContextService writingContexts;
    @Autowired private WorkImportService workImports;
    @Autowired private ImportAnalysisStore importAnalyses;
    @Autowired private ImportAnalysisRunner importAnalysisRunner;
    @Autowired private ImportedStoryBlueprintService importedPlanning;
    @MockitoSpyBean private StructuredModelGateway structuredModels;
    @Autowired private ChapterContractVersionRepository chapterContracts;
    @Autowired private ManuscriptVersionRepository chapterManuscripts;
    @Autowired private ProjectionStatusService projectionStatus;
    @MockitoBean private OutboxPublisher outboxPublisher;
    @MockitoBean private SemanticEmbeddingBackfill embeddingBackfill;
    @MockitoSpyBean private WritingGenerationGateway gateway;
    @MockitoSpyBean private ChapterReviewVersionRepository reviewRepository;
    private UUID projectId;

    private WorkImportResponse importedText(String text) {
        return workImports.upload(projectId, new MockMultipartFile("file", "source.txt", "text/plain", text.getBytes(StandardCharsets.UTF_8)));
    }
    private void stubImportAnalysis() {
        Mockito.doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var input = json.readTree((String) call.getArgument(4)); UUID chapter = UUID.fromString(input.path("slice").path("chapterId").asText());
            String quote = input.path("text").asText().contains("夹进数学书") ? "林安把纸条夹进数学书。" : "林安发现纸条的字迹很熟悉。";
            var item = new ImportAnalysis.Item("note", "CLUE", "FACT", "纸条线索", "纸条是已出现的线索，不判断作者未来意图", List.of("林安"), "SET_UP", List.of(new ImportAnalysis.Evidence(chapter, quote, 0)));
            String raw = json.writeValueAsString(new ImportAnalysis.Batch("仅核对本段原文", List.of(item)));
            ((Consumer<String>) call.getArgument(9)).accept(raw);
            return raw;
        }).when(structuredModels).request(ArgumentMatchers.eq(projectId), ArgumentMatchers.eq("IMPORT_SOURCE_ANALYSIS"), ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.anyString(), ArgumentMatchers.any(), ArgumentMatchers.anyString(), ArgumentMatchers.anyInt(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }
    private ImportAnalysisStore.View completedImportAnalysis(UUID importId) {
        stubImportAnalysis(); var view = importAnalyses.create(projectId, importId, new ImportAnalysisStore.Create(UUID.randomUUID(), ModelProvider.DEEPSEEK));
        while (view.report().status().equals("READY")) view = importAnalysisRunner.next(projectId, importId, view.report().id(), view.report().version());
        assertThat(view.report().status()).isEqualTo("REVIEW"); return view;
    }
    private ImportAnalysisStore.View acceptImportAnalysis(ImportAnalysisStore.View view, ImportPlanningMode mode) {
        return importAnalyses.confirm(projectId, view.report().importId(), view.report().id(), new ImportAnalysisStore.Confirm(view.report().version(), true, mode,
                view.report().content().items().stream().map(item -> new ImportAnalysis.Decision(item.key(), "KEEP", "")).toList()));
    }
    @Test void importAnalysisRequiresFullCoverageAndConfirmationWithoutCreatingCanon() {
        var source = importedText("第1章 纸条\n林安把纸条夹进数学书。\n第2章 字迹\n林安发现纸条的字迹很熟悉。"); stubImportAnalysis();
        var created = importAnalyses.create(projectId, source.id(), new ImportAnalysisStore.Create(UUID.randomUUID(), ModelProvider.DEEPSEEK));
        assertThat(created.report().slices()).hasSize(2);
        var first = importAnalysisRunner.next(projectId, source.id(), created.report().id(), created.report().version());
        assertThat(first.report().status()).isEqualTo("READY"); assertThat(first.report().nextSlice()).isEqualTo(1);
        assertThatThrownBy(() -> acceptImportAnalysis(first, ImportPlanningMode.ADAPT_SOURCE)).hasMessageContaining("全部原文");
        var complete = importAnalysisRunner.next(projectId, source.id(), first.report().id(), first.report().version());
        assertThat(complete.report().content().items()).hasSize(2);
        var accepted = acceptImportAnalysis(complete, ImportPlanningMode.ADAPT_SOURCE);
        assertThat(importAnalyses.requireConfirmed(projectId, source.id(), accepted.report().id(), accepted.report().version(), accepted.report().confirmedMode()).path("content").path("items")).hasSize(2);
        assertThat(characterNames.list(projectId)).isEmpty(); assertThat(readerExperiences.list(projectId)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM story_fact WHERE project_id = ?", Integer.class, projectId)).isZero();
    }
    @Test void importAnalysisRejectsMissingDecisionsAndContinuationReworkAndOldConfirmation() {
        var source = importedText("第1章 纸条\n林安把纸条夹进数学书。"); var view = completedImportAnalysis(source.id());
        assertThatThrownBy(() -> importAnalyses.confirm(projectId, source.id(), view.report().id(), new ImportAnalysisStore.Confirm(view.report().version(), true, ImportPlanningMode.ADAPT_SOURCE, List.of()))).hasMessageContaining("逐项");
        var decision = new ImportAnalysis.Decision(view.report().content().items().getFirst().key(), "REWORK", "改成未寄出的信");
        assertThatThrownBy(() -> importAnalyses.confirm(projectId, source.id(), view.report().id(), new ImportAnalysisStore.Confirm(view.report().version(), true, ImportPlanningMode.CONTINUE_MANUSCRIPT, List.of(decision)))).hasMessageContaining("续写不能重构");
        var accepted = acceptImportAnalysis(view, ImportPlanningMode.ADAPT_SOURCE);
        assertThatThrownBy(() -> importAnalyses.requireConfirmed(projectId, source.id(), accepted.report().id(), accepted.report().version(), ImportPlanningMode.CONTINUE_MANUSCRIPT)).hasMessageContaining("使用方式不同");
        acceptImportAnalysis(accepted, ImportPlanningMode.CONTINUE_MANUSCRIPT);
        assertThatThrownBy(() -> importAnalyses.requireConfirmed(projectId, source.id(), accepted.report().id(), accepted.report().version(), ImportPlanningMode.ADAPT_SOURCE)).isInstanceOf(ResourceVersionConflictException.class);
    }
    @Test void importAnalysisRejectsFakeEvidenceAndRequiresExplicitRecovery() {
        var source = importedText("第1章 纸条\n林安把纸条夹进数学书。"); var view = importAnalyses.create(projectId, source.id(), new ImportAnalysisStore.Create(UUID.randomUUID(), ModelProvider.DEEPSEEK));
        var claim = importAnalyses.claim(projectId, source.id(), view.report().id(), view.report().version());
        var item = new ImportAnalysis.Item("fake", "EVENT", "FACT", "假事件", "不存在的描述", List.of(), "NOT_APPLICABLE", List.of(new ImportAnalysis.Evidence(source.chapters().getFirst().id(), "林安烧掉了纸条。", 0)));
        assertThatThrownBy(() -> importAnalyses.finish(claim, json.writeValueAsString(new ImportAnalysis.Batch("说明", List.of(item))))).hasMessageContaining("证据不存在");
        importAnalyses.fail(claim, new IllegalArgumentException("证据不存在")); var failed = importAnalyses.get(projectId, source.id(), view.report().id());
        assertThat(failed.report().status()).isEqualTo("FAILED"); assertThat(failed.report().nextSlice()).isZero();
        assertThat(importAnalyses.action(projectId, source.id(), view.report().id(), failed.report().version(), "resume").report().status()).isEqualTo("READY");
    }
    @Test void importAnalysisCancellationRejectsLateResultsAndSourceChangesInvalidateConfirmation() {
        var source = importedText("第1章 纸条\n林安把纸条夹进数学书。"); var input = new ImportAnalysisStore.Create(UUID.randomUUID(), ModelProvider.DEEPSEEK);
        var view = importAnalyses.create(projectId, source.id(), input); assertThat(importAnalyses.create(projectId, source.id(), input).report().id()).isEqualTo(view.report().id());
        var claim = importAnalyses.claim(projectId, source.id(), view.report().id(), view.report().version());
        importAnalyses.action(projectId, source.id(), view.report().id(), claim.report().version(), "cancel");
        assertThatThrownBy(() -> importAnalyses.finish(claim, "{}" )).isInstanceOf(ResourceVersionConflictException.class);
        importAnalyses.fail(claim, new IllegalArgumentException("迟到失败")); assertThat(importAnalyses.get(projectId, source.id(), view.report().id()).report().status()).isEqualTo("CANCELLED");
        var accepted = acceptImportAnalysis(completedImportAnalysis(source.id()), ImportPlanningMode.CONTINUE_MANUSCRIPT);
        jdbc.update("UPDATE imported_chapter SET content = content || '新内容' WHERE import_id = ?", source.id());
        assertThat(importAnalyses.get(projectId, source.id(), accepted.report().id()).stale()).isTrue();
        assertThatThrownBy(() -> importAnalyses.requireConfirmed(projectId, source.id(), accepted.report().id(), accepted.report().version(), accepted.report().confirmedMode())).hasMessageContaining("原文或章节选择已变化");
    }
    @Test void importAnalysisPreventsUnconfirmedPlanningAndPrivateSourceAccess() {
        var source = importedText("第1章 纸条\n林安把纸条夹进数学书。");
        assertThatThrownBy(() -> importedPlanning.generate(projectId, source.id(), new PrepareImportedStoryRequest(ModelProvider.DEEPSEEK, ImportPlanningMode.ADAPT_SOURCE, "", UUID.randomUUID(), 0L, 50000, false))).hasMessageContaining("先解析原文");
        var foreign = projects.saveAndFlush(NovelProject.create(UUID.randomUUID(), UUID.randomUUID(), "别人项目", EntryMode.IDEA));
        assertThatThrownBy(() -> importAnalyses.list(foreign.getId(), source.id())).isInstanceOf(ProjectNotFoundException.class);
        var otherSource = importedText("第1章 新稿\n不同的稿件。"); var view = completedImportAnalysis(source.id());
        assertThatThrownBy(() -> importAnalyses.get(projectId, otherSource.id(), view.report().id())).isInstanceOf(WritingResourceNotFoundException.class);
    }
    @Test void publishedPlanningSynchronizesProfilesRelationsAndLedgerWithoutCanonOrOverwrites() {
        var character = CharacterBlueprintFixtures.character("林安");
        var template = CharacterBlueprintFixtures.bible(List.of(character));
        var seed = new ReaderExperienceSeed("letter", "FORESHADOW", "纸条签名", "找到纸条主人", "擦掉的签名", "认出字迹", "重新选择", 2);
        var content = new StoryBibleContent(template.logline(), template.theme(), template.worldSetting(), template.worldRules(),
                "林安", template.protagonistArc(), List.of(), template.relationshipDynamics(), template.centralConflict(),
                template.stakes(), template.narrativeStyle(), template.endingDirection(), template.hardConstraints(), template.openQuestions(),
                List.of(character), List.of(seed));
        var draft = bibles.saveAndFlush(StoryBibleVersion.create(UUID.randomUUID(), projectId, 2, "AUTHOR_EDIT", null, null, null, content));
        assertThat(characterNames.list(projectId)).isEmpty();
        assertThat(readerExperiences.list(projectId)).isEmpty();
        draft.publish();
        bibles.saveAndFlush(draft);
        var project = projects.findById(projectId).orElseThrow();
        project.publishStoryBible(draft.getId());
        projects.saveAndFlush(project);
        planningMaterials.syncBible(draft);
        var profile = characterProfiles.list(projectId).getFirst();
        assertThat(profile.background()).isEqualTo(character.background());
        assertThat(profile.secret()).isEqualTo(character.secret());
        assertThat(planningMaterials.characters(projectId).getFirst().blueprint()).isEqualTo(character);
        assertThat(planningMaterials.relationships(projectId, profile.characterId())).hasSize(2);
        var entry = readerExperiences.list(projectId).getFirst();
        assertThat(entry.state().name()).isEqualTo("PLANNED");
        assertThat(entry.history()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM story_relationship WHERE project_id = ?", Integer.class, projectId)).isZero();
        assertThat(projects.findById(projectId).orElseThrow().getCurrentCanonVersion()).isZero();
        jdbc.update("UPDATE character_profile SET background = '作者自己的背景' WHERE character_id = ?", profile.characterId());
        jdbc.update("UPDATE reader_experience_plan SET payoff_text = '作者自己的兑现' WHERE id = ?", entry.plan().id());
        characterNames.update(projectId, profile.characterId(), characterNames.list(projectId).getFirst().version(), "林舟", null, null);
        planningMaterials.syncCurrent(projectId);
        planningMaterials.syncCurrent(projectId);
        assertThat(characterNames.list(projectId)).hasSize(1);
        assertThat(characterProfiles.list(projectId).getFirst().background()).isEqualTo("作者自己的背景");
        assertThat(readerExperiences.list(projectId)).hasSize(1);
        assertThat(readerExperiences.list(projectId).getFirst().plan().payoff()).isEqualTo("作者自己的兑现");
        assertThat(planningMaterials.characters(projectId).getFirst().blueprint().name()).isEqualTo("林舟");
        jdbc.update("UPDATE reader_experience_plan SET deleted = TRUE WHERE id = ?", entry.plan().id());
        planningMaterials.syncCurrent(projectId);
        assertThat(readerExperiences.list(projectId)).isEmpty();
        UUID stranger = UUID.randomUUID();
        projects.saveAndFlush(NovelProject.create(stranger, UUID.randomUUID(), "其他作者", EntryMode.MATERIALS));
        assertThatThrownBy(() -> planningMaterials.syncCurrent(stranger)).isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> planningMaterials.relationships(stranger, null)).isInstanceOf(ProjectNotFoundException.class);
    }

    @Test void outlinePublicationImportsPlansAndReplacingSourceMarksOldOrigins() {
        var current = outlines.findById(projects.findById(projectId).orElseThrow().getCurrentOutlineVersionId()).orElseThrow();
        var seed = new ReaderExperienceSeed("trust", "PROMISE", "信任选择", "做出选择", "", "主动承担", "", 2);
        var value = current.getContent();
        var content = new OutlineContent(value.title(), value.premise(), value.structureSummary(), value.pacingStrategy(),
                value.suggestedMinWords(), value.suggestedMaxWords(), value.arcs(), List.of(seed));
        var draft = outlines.saveAndFlush(OutlineVersion.create(UUID.randomUUID(), projectId, 2, "AUTHOR_EDIT", null,
                current.getSourceBibleVersionId(), current.getWordBudget(), content));
        assertThat(readerExperiences.list(projectId)).isEmpty();
        outlineService.publish(projectId, draft.getId(), draft.getRowVersion());
        var oldOrigin = planningMaterials.origins(projectId).getFirst();
        assertThat(oldOrigin.current()).isTrue();
        var replacement = outlines.saveAndFlush(OutlineVersion.create(UUID.randomUUID(), projectId, 3, "AUTHOR_EDIT", null,
                current.getSourceBibleVersionId(), current.getWordBudget(), content));
        outlineService.publish(projectId, replacement.getId(), replacement.getRowVersion());
        assertThat(readerExperiences.list(projectId)).hasSize(2);
        assertThat(planningMaterials.origins(projectId).stream().filter(origin -> origin.planId().equals(oldOrigin.planId())).findFirst().orElseThrow().current()).isFalse();
        assertThat(planningMaterials.origins(projectId).stream().filter(origin -> origin.current()).count()).isEqualTo(1);
    }

    @Test void invalidLedgerChapterRollsBackOutlinePublicationAndSynchronization() {
        var current = outlines.findById(projects.findById(projectId).orElseThrow().getCurrentOutlineVersionId()).orElseThrow();
        var value = current.getContent();
        var seed = new ReaderExperienceSeed("missing", "FORESHADOW", "失踪的章", "追回线索", "", "", "", 99);
        var content = new OutlineContent(value.title(), value.premise(), value.structureSummary(), value.pacingStrategy(),
                value.suggestedMinWords(), value.suggestedMaxWords(), value.arcs(), List.of(seed));
        var draft = outlines.saveAndFlush(OutlineVersion.create(UUID.randomUUID(), projectId, 2, "AUTHOR_EDIT", null,
                current.getSourceBibleVersionId(), current.getWordBudget(), content));
        assertThatThrownBy(() -> outlineService.publish(projectId, draft.getId(), draft.getRowVersion()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(projects.findById(projectId).orElseThrow().getCurrentOutlineVersionId()).isEqualTo(current.getId());
        assertThat(outlines.findById(draft.getId()).orElseThrow().getStatus().name()).isEqualTo("DRAFT");
        assertThat(readerExperiences.list(projectId)).isEmpty();
    }

    @Test void canonForeshadowsAppearInSharedLedgerWithoutForgingAuthorProgress() {
        commitForeshadowChapter(1);
        var ledger = readerExperiences.list(projectId);
        assertThat(ledger).hasSize(1);
        assertThat(ledger.getFirst().state().name()).isEqualTo("PLANNED");
        assertThat(ledger.getFirst().history()).isEmpty();
        assertThat(planningMaterials.origins(projectId).getFirst().sourceKind()).isEqualTo("CANON");
        assertThat(planningMaterials.origins(projectId).getFirst().current()).isTrue();
        planningMaterials.syncCurrent(projectId);
        assertThat(readerExperiences.list(projectId)).hasSize(1);
        jdbc.update("UPDATE foreshadow SET canon_version_to = 2 WHERE project_id = ?", projectId);
        assertThat(planningMaterials.origins(projectId).getFirst().current()).isFalse();
    }

    private void commitForeshadowChapter(int chapter) {
        var request = new GenerateWritingRequest(ModelProvider.LOCAL_TEMPLATE, null,
                GenerationMode.REGENERATE, null, null);

        var manuscript = writing.generateManuscript(projectId, chapter, request);
        String evidence = "林安发现纸条背面有半个签名。";
        manuscript = writing.updateManuscript(projectId, manuscript.id(), manuscript.version(),
                new ManuscriptContent("纸条", evidence, "发现半个签名", List.of()));
        writing.acceptManuscript(projectId, manuscript.id(), manuscript.version());
        var review = writing.generateReview(projectId, chapter, request);
        var payload = json.convertValue(Map.of("foreshadowTitle", "纸条签名", "targetEffect", "认出写信的人",
                "foreshadowStatus", "PLANTED", "plannedResolveChapter", 2), TypedFactPayload.class);
        var fact = new FactProposal("F1", "FORESHADOW_CHANGE", "纸条签名", "埋设", "认出写信的人", evidence,
                1.0, payload, FactDecision.ACCEPTED);
        writing.approveReview(projectId, review.id(), review.version(), new ChapterReviewContent("已核对伏笔", List.of(), List.of(fact)));
        canon.commit(projectId, chapter, new CommitCanonRequest(review.id(), chapter - 1));
    }

    @Test void databasePresetsAreLiveButAppliedProjectStyleIsAnIndependentSnapshot() throws Exception {
        var presets = styles.presets(projectId);
        assertThat(presets).hasSize(12);
        var selected = presets.stream().filter(p -> p.basePresetId().equals("street-humor")).findFirst().orElseThrow();
        assertThat(selected.craft().examples()).hasSize(2);
        styles.apply(projectId, selected, styles.get(projectId).version());
        var edited = new WritingStyleProfile("校园人情", "减少幽默，庄重讲述",
                selected.sentenceRhythm(), selected.descriptionFocus(), selected.dialogueStyle(), selected.emotionalExpression(),
                selected.pacing(), selected.avoidPatterns(), selected.basePresetId(), selected.basePresetVersion(), selected.craft());
        styles.apply(projectId, edited, styles.get(projectId).version());
        assertThat(styles.get(projectId).profile()).isEqualTo(edited);
        assertThat(styles.promptContext(projectId)).contains("校园人情", "减少幽默", "段落组织：", "选座与让位");
        var next = selected.withCraft(selected.basePresetId(), 2, selected.craft());
        try {
            jdbc.update("UPDATE writing_style_preset SET active = FALSE WHERE preset_id = 'street-humor' AND preset_version = 1");
            jdbc.update("INSERT INTO writing_style_preset(preset_id, preset_version, profile, sort_order) VALUES (?, ?, CAST(? AS jsonb), ?)",
                    next.basePresetId(), 2, json.writeValueAsString(next), 7);
            assertThat(styles.presets(projectId)).contains(next).doesNotContain(selected);
            assertThat(styles.get(projectId).profile()).isEqualTo(edited);
            var stored = json.convertValue(projects.findById(projectId).orElseThrow().getSetting("writingStyle"),
                    WritingStyleProfile.class);
            assertThat(stored.craft()).isEqualTo(selected.craft());
            assertThat(stored.basePresetVersion()).isEqualTo(1);
        } finally {
            jdbc.update("DELETE FROM writing_style_preset WHERE preset_id = 'street-humor' AND preset_version = 2");
            jdbc.update("UPDATE writing_style_preset SET active = TRUE WHERE preset_id = 'street-humor' AND preset_version = 1");
        }
    }

    @Test void campusRelationshipPresetCanBeAppliedFromTheDatabaseAndUsedInPromptContext() {
        var profile = styles.presets(projectId).stream().filter(p -> p.basePresetId().equals("campus-relationships"))
                .findFirst().orElseThrow();
        assertThat(profile.name()).isEqualTo("校园关系：清爽叙事");
        assertThat(profile.craft().examples()).hasSize(2);
        var before = styles.get(projectId);
        assertThat(before.profile()).isNull();
        styles.apply(projectId, profile, before.version());
        assertThat(styles.get(projectId).profile()).isEqualTo(profile);
        assertThat(styles.promptContext(projectId)).contains("校园关系：清爽叙事", "少量机智与自嘲",
                "高潮或强开篇", "道歉：", "事实与硬约束优先", "不改事件顺序、知识、关系和结局");
        assertThat(styles.resolveProfile(new WritingStyleProfile(profile.name(),
                profile.narrativeVoice(), profile.sentenceRhythm(), profile.descriptionFocus(), profile.dialogueStyle(),
                profile.emotionalExpression(), profile.pacing(), profile.avoidPatterns(), profile.basePresetId(), 1, null))).isEqualTo(profile);
    }

    @Test void legacyPresetUpgradeRequiresFullMatchAndDisabledVersionsCanStillBeResolved() throws Exception {
        for (var base : WritingStylePresets.all()) {
            var old = new WritingStyleProfile(base.name(), base.narrativeVoice(),
                    base.sentenceRhythm(), base.descriptionFocus(), base.dialogueStyle(), base.emotionalExpression(),
                    base.pacing(), base.avoidPatterns());
            assertThat(styles.resolveProfile(old)).isEqualTo(base);
        }
        var selected = styles.presets(projectId).getFirst();
        var legacy = new WritingStyleProfile(selected.name(), selected.narrativeVoice(),
                selected.sentenceRhythm(), selected.descriptionFocus(), selected.dialogueStyle(), selected.emotionalExpression(),
                selected.pacing(), selected.avoidPatterns());
        var project = projects.findById(projectId).orElseThrow();
        project.setSetting("writingStyle", json.convertValue(legacy, new TypeReference<Map<String, Object>>() { }));
        projects.saveAndFlush(project);
        assertThat(styles.get(projectId).profile()).isEqualTo(selected);
        assertThat(json.valueToTree(projects.findById(projectId).orElseThrow().getSetting("writingStyle")).path("craft").isNull()).isTrue();
        var custom = new WritingStyleProfile(selected.name(), "与预设不同的自定义语气",
                selected.sentenceRhythm(), selected.descriptionFocus(), selected.dialogueStyle(), selected.emotionalExpression(),
                selected.pacing(), selected.avoidPatterns());
        assertThat(styles.resolveProfile(custom)).isEqualTo(custom);
        var explicit = new WritingStyleProfile("改名后的风格", "自定义语气",
                selected.sentenceRhythm(), selected.descriptionFocus(), selected.dialogueStyle(), selected.emotionalExpression(),
                selected.pacing(), selected.avoidPatterns(), selected.basePresetId(), 1, null);
        try {
            jdbc.update("UPDATE writing_style_preset SET active = FALSE WHERE preset_id = ?", selected.basePresetId());
            assertThat(styles.resolveProfile(explicit).craft()).isEqualTo(selected.craft());
            assertThat(styles.resolveProfile(explicit).name()).isEqualTo("改名后的风格");
        } finally {
            jdbc.update("UPDATE writing_style_preset SET active = TRUE WHERE preset_id = ? AND preset_version = 1", selected.basePresetId());
        }
    }

    @Test void streamedRunPersistsPrivatePartialOutputAndReadableErrorWithoutChangingBusinessState() {
        int biblesBefore = bibles.findAllByProjectIdOrderByGenerationNumberDesc(projectId).size();
        assertThatThrownBy(() -> runRecorder.record(projectId, "IMPORT_REVERSE_BIBLE", ModelProvider.LOCAL_CODEX,
                "private system", "private input", () -> {
                    var current = runQueries.list(projectId).getFirst();
                    runRecorder.progressSink().accept("{\"logline\":\"部分响应");
                    assertThat(runQueries.output(projectId, current.id()).orElseThrow().responseText()).contains("部分响应");
                    assertThat(runQueries.output(projectId, current.id()).orElseThrow().status()).isEqualTo("RUNNING");
                    throw new CodexAppServerException("等待上限 600 秒，生成超时",
                            new TimeoutException());
                })).isInstanceOf(CodexAppServerException.class);
        var record = runQueries.list(projectId).getFirst();
        var response = runQueries.output(projectId, record.id()).orElseThrow();
        assertThat(response.status()).isEqualTo("FAILED");
        assertThat(response.responseText()).contains("部分响应");
        assertThat(response.errorCategory()).isEqualTo("TIMEOUT");
        assertThat(response.errorDetail()).contains("600 秒");
        assertThat(record.errorMessage()).contains("等待超时");
        assertThat(bibles.findAllByProjectIdOrderByGenerationNumberDesc(projectId)).hasSize(biblesBefore);
        assertThat(projects.findById(projectId).orElseThrow().getCurrentCanonVersion()).isZero();
        var runId = record.id();
        UUID other = UUID.randomUUID();
        projects.saveAndFlush(NovelProject.create(other, actor.currentUserId(), "另一个项目", EntryMode.IDEA));
        assertThat(runQueries.output(other, runId)).isEmpty();
    }

    @Test
    void planningBatchRunsOneChunkPerClickAndAssemblesIdempotentDraftWithoutPublishing() throws Exception {
        var command = planningBatchCommand(4, 2);
        var batch = planningBatches.create(projectId, command);
        assertThat(planningBatches.create(projectId, command).id()).isEqualTo(batch.id());
        var inputs = stubPlanningChunks();
        batch = planningBatchRunner.runNext(projectId, batch.id(), batch.version());
        assertThat(inputs).hasSize(1);
        assertThat(inputs.getFirst().path("precedingPlans")).isEmpty();
        assertThat(inputs.getFirst().path("batchChapterCount").asInt()).isEqualTo(4);
        assertThat(batch.checkpoints()).hasSize(1);
        assertThat(batch.checkpoints().getFirst().result().arcs().getFirst().chapters()).hasSize(2);
        var savedPrefix = batch.checkpoints().getFirst();
        long staleVersion = batch.version() - 1;
        UUID id = batch.id();
        assertThatThrownBy(() -> planningBatchRunner.runNext(projectId, id, staleVersion))
                .isInstanceOf(ResourceVersionConflictException.class);
        assertThat(inputs).hasSize(1);
        batch = planningBatchRunner.runNext(projectId, batch.id(), batch.version());
        assertThat(inputs).hasSize(2);
        assertThat(inputs.getLast().path("precedingPlans")).hasSize(1);
        assertThat(batch.checkpoints().getLast().source().dependencies().getFirst().checkpointId()).isEqualTo(savedPrefix.id());
        assertThat(batch.checkpoints().getFirst().attempt()).isEqualTo(savedPrefix.attempt());
        var currentOutline = projects.findById(projectId).orElseThrow().getCurrentOutlineVersionId();
        var draft = planningBatches.assemble(projectId, batch.id(), batch.version());
        assertThat(draft.status()).isEqualTo(OutlineStatus.DRAFT);
        assertThat(draft.content().chapterCount()).isEqualTo(4);
        assertThat(planningBatches.assemble(projectId, batch.id(), batch.version()).id()).isEqualTo(draft.id());
        assertThat(projects.findById(projectId).orElseThrow().getCurrentOutlineVersionId()).isEqualTo(currentOutline);
        assertThat(projects.findById(projectId).orElseThrow().getCurrentCanonVersion()).isZero();
        assertThat(planningBatches.get(projectId, batch.id()).status())
                .isEqualTo(PlanningBatch.Status.SUCCEEDED);
    }

    @Test
    void planningBatchCancellationRejectsLateResultsAndRetainsItsSuccessfulPrefix() throws Exception {
        var inputs = stubPlanningChunks();
        var batch = planningBatches.create(projectId, planningBatchCommand(4, 2));
        batch = planningBatchRunner.runNext(projectId, batch.id(), batch.version());
        var prefix = batch.checkpoints().getFirst();
        var next = planningBatches.claimNext(projectId, batch.id(), batch.version());
        var running = checkpoints.claim(projectId, next.checkpoint().id(), next.checkpoint().version());
        var cancelled = planningBatches.cancel(projectId, batch.id(), next.batchVersion());
        assertThat(cancelled.checkpoints().getLast().status())
                .isEqualTo(PlanningCheckpoint.Status.CANCELLED);
        assertThatThrownBy(() -> checkpoints.succeed(projectId, next.checkpoint().id(), running.checkpoint().attempt(),
                planningResult(3, 4))).isInstanceOf(PlanningCheckpointException.class);
        var id = batch.id();
        assertThatThrownBy(() -> planningBatches.finish(projectId, id, next.batchVersion()))
                .isInstanceOf(ResourceVersionConflictException.class);
        var resumed = planningBatches.resume(projectId, batch.id(), cancelled.version());
        var finished = planningBatchRunner.runNext(projectId, batch.id(), resumed.version());
        assertThat(inputs).hasSize(2);
        assertThat(finished.checkpoints().getFirst()).isEqualTo(prefix);
        assertThat(finished.checkpoints().getLast().attempt()).isEqualTo(2);
    }

    @Test
    void planningBatchRejectsChangedSourcesAndCrossProjectReadsBeforeGeneration() {
        var batch = planningBatches.create(projectId, planningBatchCommand(4, 2));
        var strategy = strategies.get(projectId);
        strategies.update(projectId, CreativeStrategy.FANQIE_GRIPPING, strategy.version());
        assertThatThrownBy(() -> planningBatches.claimNext(projectId, batch.id(), batch.version()))
                .isInstanceOf(PlanningCheckpointException.class);
        assertThat(planningBatches.get(projectId, batch.id()).checkpoints()).isEmpty();
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> planningBatches.get(other, batch.id())).isInstanceOf(ProjectNotFoundException.class);
        var changed = new PlanningBatchService.CreateCommand(5, 2,
                ModelProvider.DEEPSEEK, "", batchRequestId(batch.id()), batch.bibleRowVersion(), batch.bibleId());
        assertThatThrownBy(() -> planningBatches.create(projectId, changed))
                .isInstanceOf(PlanningCheckpointException.class);
    }

    private UUID batchRequestId(UUID batchId) {
        return jdbc.queryForObject("SELECT request_id FROM planning_batch WHERE id = ?", UUID.class, batchId);
    }

    private PlanningBatchService.CreateCommand planningBatchCommand(int chapters, int chunkSize) {
        var intent = new CreativeIntent(projectId);
        intent.update("寻找失物", List.of("校园"), "青年", "林安", "误会", List.of("克制"), 6000,
                "找到", List.of(), List.of(), List.of());
        creativeIntents.saveAndFlush(intent);
        var project = projects.findById(projectId).orElseThrow();
        var bible = bibles.findById(project.getCurrentBibleVersionId()).orElseThrow();
        return new PlanningBatchService.CreateCommand(chapters, chunkSize,
                ModelProvider.DEEPSEEK, "调查过程连续", UUID.randomUUID(), bible.getRowVersion(), bible.getId());
    }

    private List<JsonNode> stubPlanningChunks() throws Exception {
        var inputs = new ArrayList<JsonNode>();
        Mockito.doAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            var input = json.readTree((String) call.getArgument(4));
            inputs.add(input);
            return json.writeValueAsString(planningResult(input.path("chapterFrom").asInt(), input.path("chapterTo").asInt()));
        }).when(structuredModels).request(ArgumentMatchers.eq(projectId),
                ArgumentMatchers.eq("PLANNING_CHECKPOINT"), ArgumentMatchers.eq(ModelProvider.DEEPSEEK),
                ArgumentMatchers.anyString(), ArgumentMatchers.anyString(),
                ArgumentMatchers.any(), ArgumentMatchers.anyString(),
                ArgumentMatchers.anyInt(), ArgumentMatchers.any());
        return inputs;
    }

    private PlanningCheckpointResult planningResult(int from, int to) {
        var chapters = IntStream.rangeClosed(from, to)
                .mapToObj(number -> new ChapterPlan(number, "线索" + number, "林安", "查证", "行动" + number,
                        "新线索", "下一步", 1000, 2000)).toList();
        return new PlanningCheckpointResult(List.of(new OutlineArc(1, "调查", "找失物",
                "误会", "线索", "信任", chapters.size() * 1000, chapters.size() * 2000, chapters)));
    }

    @Test
    void newMigrationsSupportStrategyTrilogyLedgerAndCheckpointRecovery() {
        var project = projects.findById(projectId).orElseThrow();
        var original = outlines.findById(project.getCurrentOutlineVersionId()).orElseThrow();
        var plans = IntStream.rangeClosed(1, 3)
                .mapToObj(n -> new ChapterPlan(n, "线索" + n, "林安", "核对线索", "具体行动" + n,
                        "揭示" + n, "下一步" + n, 1000, 2000)).toList();
        var arc = new OutlineArc(1, "寻找", "找回失物", "误会", "发现线索", "信任", 3000, 6000, plans);
        var outline = OutlineVersion.create(UUID.randomUUID(), projectId, 2, "AUTHOR_EDIT", null,
                original.getSourceBibleVersionId(), new OutlineWordBudget(4500, 3000, 6000, 1, 3, 1500, 1000, 2000),
                new OutlineContent("失物", "寻找失物", "三章", "推进", 3000, 6000, List.of(arc)));
        outline.publish();
        outlines.saveAndFlush(outline);
        project.publishOutline(outline.getId());
        projects.saveAndFlush(project);
        var manuscripts = new ArrayList<ManuscriptVersion>();
        for (int number = 1; number <= 3; number++) {
            var contract = chapterContracts.saveAndFlush(ChapterContractVersion.create(
                    UUID.randomUUID(), projectId, outline.getId(), number, 1, "AUTHOR_EDIT", null,
                    new ChapterContractContent("线索" + number, "林安", "核对线索", "当天",
                            List.of("教室"), List.of("查证"), List.of("纸条"), List.of("无超能力"), "继续调查",
                            List.of("暗号"), "下一步", 1000, 2000)));
            var manuscript = ManuscriptVersion.create(UUID.randomUUID(), projectId,
                    contract.getId(), number, 1, "AUTHOR_EDIT", null,
                    new ManuscriptContent("线索" + number,
                            "林安把纸条交给同桌。第" + number + "条线索终于有了回应。", "核对纸条", List.of()));
            manuscript.accept();
            manuscripts.add(chapterManuscripts.saveAndFlush(manuscript));
        }
        var view = openingReviews.get(projectId, List.of(), ModelProvider.LOCAL_TEMPLATE, null);
        assertThat(view.available()).isTrue();
        var report = openingReviews.check(projectId, List.of(), ModelProvider.LOCAL_TEMPLATE, null,
                view.source().fingerprint(), view.budget().inputLimitTokens());
        assertThat(report.reviewMode()).isEqualTo("RULES_ONLY");
        assertThat(report.content().assessments()).allMatch(value -> value.status()
                == FirstThreeChaptersContent.Status.NOT_ASSESSED);

        var input = new ReaderExperiencePlanInput(UUID.randomUUID(), null,
                ReaderExperiencePlan.Kind.FORESHADOW, "纸条", "暗号会得到解释",
                "纸条交接", "查明来源", "共同调查", 3);
        var entry = readerExperiences.create(projectId, input);
        assertThat(readerExperiences.create(projectId, input).plan().id()).isEqualTo(entry.plan().id());
        var source = readerExperiences.source(projectId, manuscripts.getFirst().getId());
        var submitted = readerExperiences.submit(projectId, entry.plan().id(),
                new ReaderExperienceSubmission(UUID.randomUUID(), entry.plan().version(),
                        ReaderExperienceState.SET_UP, source.id(), source.rowVersion(),
                        source.fingerprint(), "林安把纸条交给同桌。", "作者复核", true));
        assertThat(submitted.state()).isEqualTo(ReaderExperienceState.SET_UP);
        assertThat(submitted.history().getFirst().canon()).isFalse();
        assertThat(submitted.stale()).isFalse();
        assertThat(readerExperiences.memory(projectId).arcs().getFirst().chapters()).isEmpty();

        var checkpoint = checkpoints.create(projectId,
                new PlanningCheckpointService.CreateCommand("opening", 1, 3,
                        ModelProvider.DEEPSEEK, "规划三章"));
        var claim = checkpoints.claim(projectId, checkpoint.id(), checkpoint.version());
        var cancelled = checkpoints.cancel(projectId, checkpoint.id(), claim.checkpoint().version());
        var result = new PlanningCheckpointResult(List.of(arc));
        assertThatThrownBy(() -> checkpoints.succeed(projectId, checkpoint.id(), claim.checkpoint().attempt(), result))
                .isInstanceOf(PlanningCheckpointException.class);
        var retry = checkpoints.retry(projectId, checkpoint.id(), cancelled.version());
        var second = checkpoints.claim(projectId, checkpoint.id(), retry.version());
        checkpoints.succeed(projectId, checkpoint.id(), second.checkpoint().attempt(), result);
        assertThat(checkpoints.reuse(projectId, checkpoint.id()).result()).isEqualTo(result);

        var strategy = strategies.get(projectId);
        strategies.update(projectId, CreativeStrategy.FANQIE_GRIPPING, strategy.version());
        assertThat(openingReviews.get(projectId, List.of(), ModelProvider.LOCAL_TEMPLATE, null).latestReport().current()).isFalse();
        assertThatThrownBy(() -> checkpoints.reuse(projectId, checkpoint.id()))
                .isInstanceOf(PlanningCheckpointException.class);
        assertThat(projects.findById(projectId).orElseThrow().getCurrentCanonVersion()).isZero();
    }

    @Test
    void globalModelSettingsPersistPerUserAndRejectStaleWrites() {
        UUID owner = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        var value = new GlobalModelSettings(ModelProvider.DEEPSEEK,
                "gpt-6.1-sol", "high", "deepseek-v4-pro", 0);
        assertThat(modelSettings.find(owner)).isEmpty();
        assertThat(modelSettings.save(owner, value)).isTrue();
        assertThat(modelSettings.save(owner, value)).isFalse();
        assertThat(modelSettings.find(other)).isEmpty();
        var persisted = modelSettings.find(owner).orElseThrow();
        assertThat(persisted.version()).isEqualTo(1);
        assertThat(persisted.deepSeekModel()).isEqualTo("deepseek-v4-pro");
        var changed = new GlobalModelSettings(ModelProvider.LOCAL_CODEX,
                "gpt-6-sol", "max", "deepseek-flash", persisted.version());
        assertThat(modelSettings.save(owner, changed)).isTrue();
        assertThat(modelSettings.save(owner, changed)).isFalse();
        assertThat(modelSettings.find(owner).orElseThrow().version()).isEqualTo(2);
    }

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry properties) {
        String url = TestDatabase.url() + "?currentSchema=" + SCHEMA + ","
                + env("DB_SCHEMA", "novel_agent") + ",public&sslmode=" + env("DB_SSL_MODE", "disable");
        properties.add("spring.datasource.url", () -> url);
        properties.add("spring.flyway.default-schema", () -> SCHEMA);
        properties.add("spring.flyway.schemas", () -> SCHEMA);
        properties.add("spring.jpa.properties.hibernate.default_schema", () -> SCHEMA);
    }

    @BeforeEach
    void fixture() {
        projectId = UUID.randomUUID();
        NovelProject project = NovelProject.create(projectId, actor.currentUserId(), "自动任务验证", EntryMode.MATERIALS);
        projects.saveAndFlush(project);
        StoryBibleContent content = new StoryBibleContent("主角寻找失物", "信任", "校园", List.of("没有超能力"),
                "林安", "学会信任", List.of(), List.of(), "线索相互矛盾", "失去友情", "细腻", "找回失物", List.of(), List.of());
        StoryBibleVersion bible = StoryBibleVersion.create(UUID.randomUUID(), projectId, 1, "LOCAL_TEMPLATE", null, null, null, content);
        bible.publish();
        bibles.saveAndFlush(bible);
        var chapters = List.of(new ChapterPlan(1, "失物", "林安", "找线索", "失物", "纸条", "暗号", 1000, 2000),
                new ChapterPlan(2, "暗号", "林安", "解暗号", "对话", "秘密", "约定", 1000, 2000));
        var arc = new OutlineArc(1, "寻找", "找回失物", "误会", "发现线索", "信任", 2000, 4000, chapters);
        var budget = new OutlineWordBudget(3000, 2000, 5000, 1, 2, 1500, 1000, 2000);
        var outlineContent = new OutlineContent("失物", "寻找失物", "两章", "推进", 2000, 4000, List.of(arc));
        OutlineVersion outline = OutlineVersion.create(UUID.randomUUID(), projectId, 1, "LOCAL_TEMPLATE", null, bible.getId(), budget, outlineContent);
        outline.publish();
        outlines.saveAndFlush(outline);
        project.publishStoryBible(bible.getId());
        project.publishOutline(outline.getId());
        projects.saveAndFlush(project);
    }

    @AfterAll
    void removeIsolatedTestSchema() {
        if (!SCHEMA.matches("automation_test_[0-9a-f]{32}")) throw new IllegalStateException("非法测试 Schema");
        jdbc.execute("DROP SCHEMA " + SCHEMA + " CASCADE");
    }

    @Test
    void previewsUnpublishedFirstChapterWithoutChangingStyleManuscriptOrCanon() {
        NovelProject before = projects.findById(projectId).orElseThrow();
        OutlineVersion published = outlines.findById(before.getCurrentOutlineVersionId()).orElseThrow();
        OutlineVersion draft = outlines.saveAndFlush(OutlineVersion.create(UUID.randomUUID(), projectId, 2,
                "LOCAL_TEMPLATE", null, published.getSourceBibleVersionId(), published.getWordBudget(), published.getContent()));
        var profile = WritingStylePresets.all().getFirst();
        long projectVersion = before.getRowVersion();
        var response = stylePreviews.generate(projectId, new GenerateStylePreviewRequest(
                draft.getId(), draft.getRowVersion(), profile, ModelProvider.LOCAL_TEMPLATE, 800, null));
        assertThat(response.sourceOutlineVersionId()).isEqualTo(draft.getId());
        assertThat(response.content().body()).contains("失物", profile.name(), "仅验证试写流程");
        assertThat(outlines.findById(draft.getId()).orElseThrow().getStatus())
                .isEqualTo(OutlineStatus.DRAFT);
        NovelProject after = projects.findById(projectId).orElseThrow();
        assertThat(after.getRowVersion()).isEqualTo(projectVersion);
        assertThat(after.getCurrentOutlineVersionId()).isEqualTo(published.getId());
        assertThat(after.getCurrentCanonVersion()).isZero();
        assertThat(styles.get(projectId).profile()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM manuscript_version WHERE project_id = ?", Long.class, projectId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chapter_contract_version WHERE project_id = ?", Long.class, projectId)).isZero();
    }

    @Test
    void previewEditingPersistsReportClaimsOneRevisionAndDoesNotChangeNovel() {
        var project = projects.findById(projectId).orElseThrow();
        var outline = outlines.findById(project.getCurrentOutlineVersionId()).orElseThrow();
        var text = new WritingStylePreviewContent("失物", "然后他走到门口，接着看见纸条，随后停下。。");
        var input = new GenerateStylePreviewRequest(outline.getId(), outline.getRowVersion(),
                styles.presets(projectId).getFirst(), ModelProvider.LOCAL_TEMPLATE, 800, null);
        var report = previewEditing.check(projectId, new CheckStylePreviewRequest(input, text));
        assertThat(report.reviewMode()).isEqualTo("RULES");
        assertThat(report.content().issues()).isNotEmpty();
        var saved = previewReviewStore.get(projectId, report.id());
        var revisedText = new WritingStylePreviewContent("失物", "他在门口看见纸条，停了下来。");
        Mockito.doReturn(revisedText).when(gateway).reviseStylePreview(ArgumentMatchers.eq(projectId),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.eq(ModelProvider.DEEPSEEK), ArgumentMatchers.anyString());
        var selection = new ReviseStylePreviewRequest(ModelProvider.DEEPSEEK,
                List.of(report.content().issues().getFirst().id()), null);
        assertThat(previewEditing.revise(projectId, report.id(), selection).content()).isEqualTo(revisedText);
        assertThat(previewReviewStore.get(projectId, report.id()).isRevisionAttempted()).isTrue();
        assertThat(previewReviewStore.get(projectId, report.id()).getSource().content()).isEqualTo(text);
        assertThatThrownBy(() -> previewEditing.revise(projectId, report.id(), selection)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> previewReviewStore.get(UUID.randomUUID(), report.id())).isInstanceOf(ProjectNotFoundException.class);
        var after = projects.findById(projectId).orElseThrow();
        assertThat(after.getRowVersion()).isEqualTo(project.getRowVersion());
        assertThat(after.getCurrentCanonVersion()).isZero();
        assertThat(styles.get(projectId).profile()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM manuscript_version WHERE project_id = ?", Long.class, projectId)).isZero();
        var snapshot = previewReviewStore.snapshot(projectId, saved.getSource());
        jdbc.update("UPDATE story_bible_version SET row_version = row_version + 1 WHERE id = ?", outline.getSourceBibleVersionId());
        assertThatThrownBy(() -> previewReviewStore.save(projectId, snapshot, report.content())).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void recommendsFromSavedDraftBibleWithoutChangingProjectStyleOrCanon() {
        NovelProject before = projects.findById(projectId).orElseThrow();
        var published = bibles.findById(before.getCurrentBibleVersionId()).orElseThrow();
        var draft = bibles.saveAndFlush(StoryBibleVersion.create(UUID.randomUUID(), projectId, 2,
                "AUTHOR_EDIT", null, null, null, published.getContent()));
        var recommendation = new WritingStyleRecommendationContent("主题适合具体关系描写",
                List.of(new WritingStyleRecommendationContent.Recommendation("现实细腻",
                        "信任通过互动呈现", "保留调查线索", List.of(
                                new WritingStyleRecommendationContent.Evidence("theme", "信任")))));
        Mockito.doReturn(recommendation).when(gateway).recommendStyle(
                ArgumentMatchers.eq(projectId), ArgumentMatchers.any(),
                ArgumentMatchers.eq(ModelProvider.DEEPSEEK), ArgumentMatchers.isNull());
        var response = styleRecommendations.recommend(projectId, new RecommendWritingStyleRequest(
                draft.getId(), draft.getRowVersion(), ModelProvider.DEEPSEEK, null));
        assertThat(response.sourceBibleVersionId()).isEqualTo(draft.getId());
        assertThat(response.recommendations().getFirst().profile().name()).isEqualTo("现实细腻");
        assertThat(bibles.findById(draft.getId()).orElseThrow().getStatus())
                .isEqualTo(StoryBibleStatus.DRAFT);
        var after = projects.findById(projectId).orElseThrow();
        assertThat(after.getRowVersion()).isEqualTo(before.getRowVersion());
        assertThat(after.getCurrentBibleVersionId()).isEqualTo(before.getCurrentBibleVersionId());
        assertThat(after.getCurrentOutlineVersionId()).isEqualTo(before.getCurrentOutlineVersionId());
        assertThat(after.getCurrentCanonVersion()).isZero();
        assertThat(styles.get(projectId).profile()).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM manuscript_version WHERE project_id = ?", Long.class, projectId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chapter_contract_version WHERE project_id = ?", Long.class, projectId)).isZero();
    }

    @Test
    void rejectsBibleRecommendationChangedDuringGeneration() {
        var project = projects.findById(projectId).orElseThrow();
        var published = bibles.findById(project.getCurrentBibleVersionId()).orElseThrow();
        var draft = bibles.saveAndFlush(StoryBibleVersion.create(UUID.randomUUID(), projectId, 2,
                "AUTHOR_EDIT", null, null, null, published.getContent()));
        Mockito.doAnswer(call -> {
            var changed = bibles.findById(draft.getId()).orElseThrow();
            var content = published.getContent();
            changed.revise(new StoryBibleContent(content.logline(), "新主题", content.worldSetting(), content.worldRules(),
                    content.protagonist(), content.protagonistArc(), content.supportingCharacters(), content.relationshipDynamics(),
                    content.centralConflict(), content.stakes(), content.narrativeStyle(), content.endingDirection(),
                    content.hardConstraints(), content.openQuestions()));
            bibles.saveAndFlush(changed);
            return new WritingStyleRecommendationContent("仅验证流程", List.of());
        }).when(gateway).recommendStyle(ArgumentMatchers.eq(projectId), ArgumentMatchers.any(),
                ArgumentMatchers.eq(ModelProvider.LOCAL_TEMPLATE), ArgumentMatchers.isNull());
        assertThatThrownBy(() -> styleRecommendations.recommend(projectId,
                new RecommendWritingStyleRequest(draft.getId(), draft.getRowVersion(),
                        ModelProvider.LOCAL_TEMPLATE, null)))
                .isInstanceOf(ResourceVersionConflictException.class);
        assertThat(styles.get(projectId).profile()).isNull();
    }

    @Test
    void persistsStepsAcrossTransactionsAndFencesOldAttempt() {
        var request = new CreateAutomationRunRequest(1, 2, ModelProvider.LOCAL_TEMPLATE, null);
        UUID key = UUID.randomUUID();
        var created = store.create(projectId, key, request);
        assertThat(store.create(projectId, key, request).getId()).isEqualTo(created.getId());
        var claimed = store.claim(projectId, created.getId());
        assertThatThrownBy(() -> store.claim(projectId, created.getId())).isInstanceOf(IllegalStateException.class);
        store.update(projectId, claimed.getId(), claimed.getAttempt(), run -> run.beginStep("CONTRACT"));
        UUID artifact = UUID.randomUUID();
        store.update(projectId, claimed.getId(), claimed.getAttempt(), run -> run.completeStep(artifact));
        store.update(projectId, claimed.getId(), claimed.getAttempt(), run -> run.waitForUser("确认合同"));
        assertThat(store.get(projectId, claimed.getId()).getSteps().getFirst().artifactId()).isEqualTo(artifact);
        var resumed = store.claim(projectId, claimed.getId());
        assertThat(store.update(projectId, claimed.getId(), claimed.getAttempt(), run -> run.fail("OLD_WORKER"))).isFalse();
        assertThat(store.get(projectId, claimed.getId()).getStatus()).isEqualTo(AutomationStatus.RUNNING);
        store.cancel(projectId, claimed.getId());
        store.update(projectId, claimed.getId(), resumed.getAttempt(), run -> run.checkpoint());
        assertThat(store.get(projectId, claimed.getId()).getStatus()).isEqualTo(AutomationStatus.CANCELLED);
    }

    @Test
    void rejectsDuplicateActiveTasksMismatchedKeysAndOtherOwners() {
        var request = new CreateAutomationRunRequest(1, 2, ModelProvider.LOCAL_TEMPLATE, null);
        UUID key = UUID.randomUUID();
        store.create(projectId, key, request);
        assertThatThrownBy(() -> store.create(projectId, UUID.randomUUID(), request)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.create(projectId, key, new CreateAutomationRunRequest(1, 1, ModelProvider.LOCAL_TEMPLATE, null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> store.create(projectId, key, new CreateAutomationRunRequest(1, 2, ModelProvider.LOCAL_TEMPLATE, null, true)))
                .isInstanceOf(IllegalArgumentException.class);
        NovelProject other = NovelProject.create(UUID.randomUUID(), UUID.randomUUID(), "其他作者", EntryMode.MATERIALS);
        projects.saveAndFlush(other);
        assertThatThrownBy(() -> store.list(other.getId())).isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void writesTwoChaptersThroughRealServicesWithAuthorGates() throws Exception {
        var request = new CreateAutomationRunRequest(1, 2, ModelProvider.LOCAL_TEMPLATE, null);
        UUID key = UUID.randomUUID();
        UUID id = automation.create(projectId, key, request).id();
        assertThat(automation.create(projectId, key, request).id()).isEqualTo(id);
        for (int chapter = 1; chapter <= 2; chapter++) {
            awaitWaiting(id);

            var manuscript = writing.latestManuscript(projectId, chapter).orElseThrow();
            assertThat(writing.latestReview(projectId, chapter)).isEmpty();
            writing.acceptManuscript(projectId, manuscript.id(), manuscript.version());
            automation.resume(projectId, id);
            awaitWaiting(id);
            var review = writing.latestReview(projectId, chapter).orElseThrow();
            assertThat(automation.get(projectId, id).currentChapter()).isEqualTo(chapter);
            var rejectedFacts = review.content().factProposals().stream().map(fact -> new FactProposal(
                    fact.id(), fact.factType(), fact.subject(), fact.predicate(), fact.object(), fact.evidence(),
                    fact.confidence(), fact.payload(), FactDecision.REJECTED)).toList();
            writing.approveReview(projectId, review.id(), review.version(),
                    new ChapterReviewContent(review.content().summary(), review.content().issues(), rejectedFacts));
            canon.commit(projectId, chapter, new CommitCanonRequest(review.id(), chapter - 1));
            automation.resume(projectId, id);
        }
        awaitSettled(id);
        assertThat(automation.get(projectId, id).status()).isEqualTo(AutomationStatus.SUCCEEDED);
        assertThat(automation.get(projectId, id).steps()).hasSize(4);
        assertThat(projects.findById(projectId).orElseThrow().getCurrentCanonVersion()).isEqualTo(2);
    }

    @Test
    void automationChecksQualityReusesCurrentReportAndRechecksChangesWithoutAutoAccepting() throws Exception {
        UUID id = automation.create(projectId, UUID.randomUUID(),
                new CreateAutomationRunRequest(1, 1, ModelProvider.LOCAL_TEMPLATE, null, true)).id();
        awaitWaiting(id);
        assertThat(automation.get(projectId, id).qualityReviewEnabled()).isTrue();

        var report = quality.latest(projectId, 1).orElseThrow();
        assertThat(report.current()).isTrue();
        assertThat(automation.get(projectId, id).steps()).extracting(step -> step.stage())
                .containsExactly("MANUSCRIPT", "QUALITY_REVIEW");
        var manuscript = writing.latestManuscript(projectId, 1).orElseThrow();
        assertThat(manuscript.status().name()).isEqualTo("DRAFT");
        assertThat(writing.latestReview(projectId, 1)).isEmpty();
        automation.resume(projectId, id);
        awaitWaiting(id);
        assertThat(quality.latest(projectId, 1).orElseThrow().id()).isEqualTo(report.id());
        assertThat(automation.get(projectId, id).steps()).hasSize(2);
        manuscript = writing.updateManuscript(projectId, manuscript.id(), manuscript.version(),
                new ManuscriptContent("纸条", "然后他走到门口，接着看见纸条，随后停下来。。", "发现纸条", List.of()));
        automation.resume(projectId, id);
        awaitWaiting(id);
        var updatedReport = quality.latest(projectId, 1).orElseThrow();
        assertThat(updatedReport.id()).isNotEqualTo(report.id());
        assertThat(updatedReport.content().issues()).isNotEmpty();
        assertThat(automation.get(projectId, id).waitingReason()).contains("选择是否润色");
        styles.apply(projectId, styles.presets(projectId).getFirst(), styles.get(projectId).version());
        automation.resume(projectId, id);
        awaitWaiting(id);
        var styleReport = quality.latest(projectId, 1).orElseThrow();
        assertThat(styleReport.id()).isNotEqualTo(updatedReport.id());
        var revised = quality.revise(projectId, 1, styleReport.id(), List.of(styleReport.content().issues().getFirst().id()), ModelProvider.LOCAL_TEMPLATE, null,
                ReviseQualityRequest.Scope.SCENE_STRUCTURE);
        automation.resume(projectId, id);
        awaitWaiting(id);
        var revisedReport = quality.latest(projectId, 1).orElseThrow();
        assertThat(revisedReport.sourceManuscriptId()).isEqualTo(revised.id());
        assertThat(revisedReport.current()).isTrue();
        writing.acceptManuscript(projectId, revised.id(), revised.version());
        automation.resume(projectId, id);
        awaitWaiting(id);
        assertThat(quality.latest(projectId, 1).orElseThrow().id()).isEqualTo(revisedReport.id());
        assertThat(writing.latestReview(projectId, 1)).isPresent();
        assertThat(projects.findById(projectId).orElseThrow().getCurrentCanonVersion()).isZero();
        automation.cancel(projectId, id);
    }

    @Test
    void persistsEditableStyleAndVersionBoundQualityRevisionWithoutChangingOriginal() throws Exception {
        var analysis = styleAnalysis.analyze(projectId, "他走到门口，停下脚步。她把纸条递给他，没有说话。".repeat(10), ModelProvider.LOCAL_TEMPLATE);
        assertThat(styles.get(projectId).profile()).isNull();
        assertThat(analysis.analysisMode()).isEqualTo("TEXT_METRICS");
        var selected = styles.apply(projectId, analysis.profile(), styles.get(projectId).version());
        assertThat(styles.get(projectId).profile()).isEqualTo(analysis.profile());
        assertThatThrownBy(() -> styles.apply(projectId, styles.presets(projectId).getFirst(), selected.version() - 1))
                .isInstanceOf(ResourceVersionConflictException.class);
        var request = new GenerateWritingRequest(ModelProvider.LOCAL_TEMPLATE, null,
                GenerationMode.REGENERATE, null, null);

        var manuscript = writing.generateManuscript(projectId, 1, request);
        String repeated = "然后林安走到门口，接着看见纸条，随后停下脚步。。";
        var content = new ManuscriptContent("失物", repeated + "\n" + repeated, "林安发现纸条", List.of());
        manuscript = writing.updateManuscript(projectId, manuscript.id(), manuscript.version(), content);
        var report = quality.generate(projectId, 1, ModelProvider.LOCAL_TEMPLATE, null);
        assertThat(report.current()).isTrue();
        assertThat(report.content().issues()).isNotEmpty();
        var oldSnapshot = qualityStore.snapshot(projectId, 1);
        var oldContent = report.content();
        content = new ManuscriptContent("失物", repeated + "\n" + repeated,
                "林安发现纸条，尚未理解内容", List.of());
        manuscript = writing.updateManuscript(projectId, manuscript.id(), manuscript.version(), content);
        assertThat(quality.latest(projectId, 1).orElseThrow().current()).isFalse();
        assertThatThrownBy(() -> qualityStore.save(oldSnapshot, "LOCAL_TEMPLATE", null, oldContent))
                .isInstanceOf(IllegalStateException.class);
        report = quality.generate(projectId, 1, ModelProvider.LOCAL_TEMPLATE, null);
        styles.apply(projectId, styles.presets(projectId).getFirst(), styles.get(projectId).version());
        assertThat(quality.latest(projectId, 1).orElseThrow().current()).isFalse();
        UUID oldReport = report.id();
        assertThatThrownBy(() -> quality.revise(projectId, 1, oldReport, List.of("Q1"), ModelProvider.LOCAL_TEMPLATE, null))
                .isInstanceOf(IllegalStateException.class);
        report = quality.generate(projectId, 1, ModelProvider.LOCAL_TEMPLATE, null);
        var revision = quality.revise(projectId, 1, report.id(), List.of(report.content().issues().getFirst().id()), ModelProvider.LOCAL_TEMPLATE, null,
                ReviseQualityRequest.Scope.SCENE_STRUCTURE);
        assertThat(revision.baseManuscriptVersionId()).isEqualTo(manuscript.id());
        assertThat(revision.content()).isEqualTo(content);
        assertThat(revision.changeSummary()).contains("本地模板仅创建版本流程候选，保留原稿，未执行语义润色。");
        assertThat(revision.status().name()).isEqualTo("DRAFT");
        assertThat(writing.manuscriptVersion(projectId, 1, manuscript.id()).content()).isEqualTo(content);
        assertThat(quality.latest(projectId, 1).orElseThrow().current()).isFalse();
        assertThat(projects.findById(projectId).orElseThrow().getCurrentCanonVersion()).isZero();
        styles.apply(projectId, null, styles.get(projectId).version());
        assertThat(styles.get(projectId).profile()).isNull();
    }

    private void awaitWaiting(UUID id) throws Exception {
        awaitSettled(id);
        assertThat(automation.get(projectId, id).status()).isEqualTo(AutomationStatus.WAITING_FOR_USER);
    }

    @Test
    void returnedReviewAndCandidateRollBackTogetherWhenSavingReviewFails() {
        var request = new GenerateWritingRequest(ModelProvider.LOCAL_TEMPLATE, null,
                GenerationMode.REGENERATE, null, null);

        var draft = writing.generateManuscript(projectId, 1, request);
        writing.acceptManuscript(projectId, draft.id(), draft.version());
        var generatedReview = writing.generateReview(projectId, 1, request);
        var review = writing.updateReview(projectId, generatedReview.id(), generatedReview.version(),
                new ChapterReviewContent("修改标点", List.of(new ReviewIssue(
                        "I1", "INFO", "FLUENCY", "复核标点", "纸条", "保留事实，调整标点", false)), List.of()));
        int before = writing.manuscriptVersions(projectId, 1).size();
        Mockito.doThrow(new IllegalStateException("受控保存失败")).when(reviewRepository)
                .saveAndFlush(ArgumentMatchers.argThat(value -> value.getId().equals(review.id())
                        && value.getStatus() == ReviewStatus.RETURNED));
        assertThatThrownBy(() -> writing.returnReviewToWriting(projectId, 1, review.id(), review.version(),
                new ReturnReviewRequest(ModelProvider.LOCAL_TEMPLATE,
                        GenerationMode.REVISE, List.of("I1"), null)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("受控保存失败");
        assertThat(writing.manuscriptVersions(projectId, 1)).hasSize(before);
        assertThat(writing.latestManuscript(projectId, 1).orElseThrow().id()).isEqualTo(draft.id());
        assertThat(writing.latestReview(projectId, 1).orElseThrow().status().name()).isEqualTo("DRAFT");
        assertThat(projects.findById(projectId).orElseThrow().getCurrentCanonVersion()).isZero();
    }

    @Test
    void queryLayersReadOwnedRowsAndNeverExposeAnotherProjectsPrompt() {
        UUID runId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO agent_run (id, project_id, stage, provider, status, prompt_preview, system_prompt, user_prompt,
                                       input_tokens, output_tokens, estimated_cost)
                VALUES (?, ?, 'QUALITY_REVIEW', 'LOCAL_TEMPLATE', 'SUCCEEDED', 'preview', 'system', 'user', 10, 5, 0.01)
                """, runId, projectId);
        assertThat(runQueries.list(projectId)).hasSize(1);
        var summary = runQueries.summary(projectId);
        assertThat(summary.calls()).isEqualTo(1);
        assertThat(summary.inputTokens()).isEqualTo(10);
        assertThat(summary.outputTokens()).isEqualTo(5);
        assertThat(summary.estimatedCost()).isEqualByComparingTo("0.01");
        assertThat(runQueries.prompt(projectId, runId).orElseThrow().userPrompt()).isEqualTo("user");
        assertThat(runQueries.prompt(projectId, UUID.randomUUID())).isEmpty();
        var status = projectionStatus.status(projectId);
        assertThat(status.canonVersion()).isZero();
        assertThat(status.kafkaPublished()).isFalse();
        assertThat(status.pgvectorProjected()).isFalse();
        assertThat(status.neo4jProjected()).isFalse();
        var foreign = NovelProject.create(UUID.randomUUID(), UUID.randomUUID(), "其他作者", EntryMode.IDEA);
        projects.saveAndFlush(foreign);
        assertThatThrownBy(() -> runQueries.list(foreign.getId())).isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> runQueries.summary(foreign.getId())).isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> runQueries.prompt(foreign.getId(), runId)).isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> projectionStatus.status(foreign.getId())).isInstanceOf(ProjectNotFoundException.class);
    }

    @Test
    void generationBudgetSurvivesPersistenceAndRepeatedResumes() throws Exception {
        UUID key = UUID.randomUUID();
        var request = new CreateAutomationRunRequest(1, 1, ModelProvider.LOCAL_TEMPLATE, null, false, 0, 1);
        UUID id = automation.create(projectId, key, request).id();
        awaitWaiting(id);
        assertThat(automation.get(projectId, id).waitingReason()).contains("作者确认正文");
        var draft = writing.latestManuscript(projectId, 1).orElseThrow();
        assertThat(draft.sourceContractVersionId()).isNull();
        assertThat(draft.writingBasis().outlineId()).isEqualTo(projects.findById(projectId).orElseThrow().getCurrentOutlineVersionId());
        writing.acceptManuscript(projectId, draft.id(), draft.version());
        automation.resume(projectId, id);
        awaitWaiting(id);
        assertThat(automation.get(projectId, id).waitingReason()).contains("生成次数上限");
        assertThat(automation.get(projectId, id).usedGenerationSteps()).isEqualTo(1);
        assertThat(writing.latestManuscript(projectId, 1)).isPresent();
        assertThat(writing.latestContract(projectId, 1)).isEmpty();
        assertThat(writing.latestContractReview(projectId, 1)).isEmpty();
        automation.resume(projectId, id);
        awaitWaiting(id);
        assertThat(automation.get(projectId, id).usedGenerationSteps()).isEqualTo(1);
        assertThat(store.create(projectId, key, request).getMaxGenerationSteps()).isEqualTo(1);
        assertThatThrownBy(() -> store.create(projectId, key,
                new CreateAutomationRunRequest(1, 1, ModelProvider.LOCAL_TEMPLATE, null, false, 0, 2)))
                .isInstanceOf(IllegalArgumentException.class);
        automation.cancel(projectId, id);
        assertThatThrownBy(() -> store.create(projectId, UUID.randomUUID(),
                new CreateAutomationRunRequest(1, 1, ModelProvider.LOCAL_TEMPLATE, null, true, 1, 100)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void automaticRevisionUsesRealGraphAndPersistenceWithControlledModelOutput() throws Exception {
        var local = new GenerateWritingRequest(ModelProvider.LOCAL_TEMPLATE, null,
                GenerationMode.REGENERATE, null, null);

        var initial = writing.generateManuscript(projectId, 1, local);
        String body = "林安停在门口。他看见了纸条。。";
        var original = writing.updateManuscript(projectId, initial.id(), initial.version(),
                new ManuscriptContent("纸条", body, "林安发现纸条", List.of()));
        Mockito.doAnswer(call -> {
            ManuscriptContent source = call.getArgument(3);
            var issues = source.body().contains("。。") ? List.of(new ReviewIssue(
                    "Q1", "INFO", "FLUENCY", "连续标点", "。。", "改为一个句号", false)) : List.<ReviewIssue>of();
            var scores = Arrays.stream(QualityDimension.values())
                    .map(d -> new QualityScore(d, null, "受控输出，不是模型质量评测")).toList();
            return new QualityReviewContent("受控质量检查", scores, issues);
        }).when(gateway).qualityReview(ArgumentMatchers.eq(projectId), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq(ModelProvider.DEEPSEEK), ArgumentMatchers.any());
        Mockito.doAnswer(call -> {
            ManuscriptContent source = call.getArgument(6);
            String feedback = call.getArgument(8);
            assertThat(feedback).contains("保持事件", "只修改", "连续标点");
            return new GeneratedManuscript(
                    new ManuscriptContent(source.title(), source.body().replace("。。", "。"),
                            source.summary(), source.continuityNotes()), List.of("删除重复句号"));
        }).when(gateway).manuscript(ArgumentMatchers.eq(projectId), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq(ModelProvider.DEEPSEEK), ArgumentMatchers.any());
        UUID key = UUID.randomUUID();
        var request = new CreateAutomationRunRequest(1, 1, ModelProvider.DEEPSEEK, null, true, 1, 10);
        UUID id = automation.create(projectId, key, request).id();
        awaitWaiting(id);
        var run = automation.get(projectId, id);
        assertThat(run.steps()).extracting(step -> step.stage()).containsExactly("QUALITY_REVIEW", "QUALITY_REVISION", "QUALITY_REVIEW");
        assertThat(run.usedAutoRevisionRounds()).isEqualTo(1);
        assertThat(run.maxAutoRevisionRounds()).isEqualTo(1);
        assertThat(run.usedGenerationSteps()).isEqualTo(3);
        var revised = writing.latestManuscript(projectId, 1).orElseThrow();
        assertThat(revised.content().body()).isEqualTo(body.replace("。。", "。"));
        assertThat(revised.baseManuscriptVersionId()).isEqualTo(original.id());
        assertThat(revised.status().name()).isEqualTo("DRAFT");
        assertThat(writing.manuscriptVersion(projectId, 1, original.id()).content().body()).isEqualTo(body);
        assertThat(quality.latest(projectId, 1).orElseThrow().sourceManuscriptId()).isEqualTo(revised.id());
        assertThat(quality.latest(projectId, 1).orElseThrow().content().issues()).isEmpty();
        assertThat(projects.findById(projectId).orElseThrow().getCurrentCanonVersion()).isZero();
        automation.resume(projectId, id);
        awaitWaiting(id);
        assertThat(automation.get(projectId, id).usedGenerationSteps()).isEqualTo(3);
        assertThatThrownBy(() -> store.create(projectId, key,
                new CreateAutomationRunRequest(1, 1, ModelProvider.DEEPSEEK, null, true, 2, 10)))
                .isInstanceOf(IllegalArgumentException.class);
        automation.cancel(projectId, id);
    }

    private void awaitSettled(UUID id) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (automation.get(projectId, id).status() == AutomationStatus.RUNNING && System.nanoTime() < deadline) {
            Thread.sleep(50);
        }
        assertThat(automation.get(projectId, id).status()).isNotEqualTo(AutomationStatus.RUNNING);
    }

}
