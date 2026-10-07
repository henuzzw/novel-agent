package com.novelagent.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.planning.api.GenerateOutlineRequest;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.OutlineWordBudgetPolicy;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.CreativeIntent;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.infrastructure.CreativeIntentRepository;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutlineServiceVersionSelectionTest {
    private final UUID projectId = UUID.randomUUID();
    private final UUID bibleId = UUID.randomUUID();
    private final OutlineWordBudget budget = new OutlineWordBudget(
            120_000, 110_000, 130_000, 2, 40, 3_000, 2_400, 3_600);
    private final NovelProjectRepository projects = mock(NovelProjectRepository.class);
    private final CreativeIntentRepository intents = mock(CreativeIntentRepository.class);
    private final StoryBibleVersionRepository bibles = mock(StoryBibleVersionRepository.class);
    private final OutlineVersionRepository outlines = mock(OutlineVersionRepository.class);
    private final OutlineWordBudgetPolicy budgetPolicy = mock(OutlineWordBudgetPolicy.class);
    private final OutlineGenerationWorkflow workflow = mock(OutlineGenerationWorkflow.class);
    private final CurrentActorProvider actor = mock(CurrentActorProvider.class);
    private final CharacterNameService characterNames = mock(CharacterNameService.class);
    private final OutlineService service = new OutlineService(
            projects, intents, bibles, outlines, budgetPolicy, workflow, new ProjectAccessService(projects, actor), characterNames, mock(PlanningMaterialSyncService.class));

    @BeforeEach
    void setUp() {
        UUID userId = UUID.randomUUID();
        NovelProject project = mock(NovelProject.class);
        StoryBibleVersion bible = mock(StoryBibleVersion.class);
        CreativeIntent intent = mock(CreativeIntent.class);
        when(actor.currentUserId()).thenReturn(userId);
        when(project.getOwnerId()).thenReturn(userId);
        when(project.getCurrentBibleVersionId()).thenReturn(bibleId);
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(bibles.findByIdAndProjectId(bibleId, projectId)).thenReturn(Optional.of(bible));
        when(bible.getStatus()).thenReturn(StoryBibleStatus.PUBLISHED);
        when(bible.getContent()).thenReturn(bibleContent());
        when(intents.findById(projectId)).thenReturn(Optional.of(intent));
        when(intent.getTargetWords()).thenReturn(120_000);
        when(budgetPolicy.plan(120_000)).thenReturn(budget);
        when(outlines.saveAndFlush(any(OutlineVersion.class))).thenAnswer(call -> call.getArgument(0));
        when(characterNames.render(eq(projectId), any(OutlineContent.class), eq(OutlineContent.class)))
                .thenAnswer(call -> call.getArgument(1));
        when(characterNames.render(eq(projectId), any(StoryBibleContent.class), eq(StoryBibleContent.class)))
                .thenAnswer(call -> call.getArgument(1));
    }

    @Test
    void revisesSelectedOlderVersionAndKeepsMonotonicGenerationNumber() {
        OutlineVersion older = version(2, "喜欢的旧版");
        OutlineVersion latest = version(3, "不满意的新草稿");
        OutlineContent resultContent = content("基于旧版微调");
        when(outlines.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)).thenReturn(Optional.of(latest));
        when(outlines.findByIdAndProjectId(older.getId(), projectId)).thenReturn(Optional.of(older));
        when(workflow.generate(eq(projectId), any(), eq(budget), eq(ModelProvider.LOCAL_TEMPLATE),
                eq(older.getContent()), eq("突出人物性格"), eq(standardPolicy())))
                .thenReturn(new GeneratedOutline("LOCAL_TEMPLATE", resultContent, List.of("强化性格")));

        var result = service.generate(projectId, new GenerateOutlineRequest(
                ModelProvider.LOCAL_TEMPLATE, "突出人物性格", GenerationMode.REVISE, older.getId()));

        assertThat(result.generationNumber()).isEqualTo(4);
        assertThat(result.status()).isEqualTo(OutlineStatus.DRAFT);
        assertThat(result.baseOutlineVersionId()).isEqualTo(older.getId());
        assertThat(result.content().title()).isEqualTo("基于旧版微调");
        verify(workflow).generate(eq(projectId), any(), eq(budget), eq(ModelProvider.LOCAL_TEMPLATE),
                eq(older.getContent()), eq("突出人物性格"), eq(standardPolicy()));
    }

    @Test
    void passesSavedPolicySeparatelyWithoutAddingSystemTextToAuthorInstruction() {
        var project = NovelProject.create(projectId, actor.currentUserId(), "测试小说",
                com.novelagent.project.domain.EntryMode.IDEA);
        project.publishStoryBible(bibleId);
        var policy = CreativeStrategyPolicy.of(CreativeStrategy.FANQIE_GRIPPING);
        policy.applyTo(project);
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(outlines.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)).thenReturn(Optional.empty());
        String author = "开头是成年后发现照片缺失。\n保留回忆框架。";
        when(workflow.generate(projectId, bibleContent(), budget, ModelProvider.LOCAL_CODEX, null, author, policy))
                .thenReturn(new GeneratedOutline("LOCAL_CODEX", content("新大纲")));

        var result = service.generate(projectId, new GenerateOutlineRequest(
                ModelProvider.LOCAL_CODEX, "  " + author + "  ", GenerationMode.REGENERATE, null));

        verify(workflow).generate(projectId, bibleContent(), budget, ModelProvider.LOCAL_CODEX, null, author, policy);
        assertThat(result.authorInstruction()).isEqualTo(author);
        assertThat(result.status()).isEqualTo(OutlineStatus.DRAFT);
        assertThat(project.getCurrentOutlineVersionId()).isNull();
    }

    @Test
    void defaultsToLatestVersionWhenNoBaseIsSelected() {
        OutlineVersion latest = version(3, "最新草稿");
        when(outlines.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)).thenReturn(Optional.of(latest));
        when(workflow.generate(eq(projectId), any(), eq(budget), eq(ModelProvider.LOCAL_TEMPLATE),
                eq(latest.getContent()), eq(null), eq(standardPolicy())))
                .thenReturn(new GeneratedOutline("LOCAL_TEMPLATE", content("微调结果")));

        var result = service.generate(projectId, new GenerateOutlineRequest(
                ModelProvider.LOCAL_TEMPLATE, null, GenerationMode.REVISE, null));

        assertThat(result.baseOutlineVersionId()).isEqualTo(latest.getId());
        assertThat(result.generationNumber()).isEqualTo(4);
    }

    @Test
    void rendersSelectedOutlineAndBibleNamesBeforeSendingThemToModel() {
        OutlineVersion older = version(1, "旧人物名的大纲");
        OutlineVersion latest = version(2, "最新草稿");
        OutlineContent renderedBase = content("按人物配置显示的新名字");
        StoryBibleContent renderedBible = new StoryBibleContent("故事", "主题", "世界", List.of(),
                "新名字", "弧光", List.of(), List.of(), "冲突", "代价", "文风", "结局", List.of(), List.of());
        when(outlines.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)).thenReturn(Optional.of(latest));
        when(outlines.findByIdAndProjectId(older.getId(), projectId)).thenReturn(Optional.of(older));
        when(characterNames.render(projectId, older.getContent(), OutlineContent.class)).thenReturn(renderedBase);
        when(characterNames.render(projectId, bibleContent(), StoryBibleContent.class)).thenReturn(renderedBible);
        when(workflow.generate(projectId, renderedBible, budget, ModelProvider.LOCAL_CODEX,
                renderedBase, "微调", standardPolicy()))
                .thenReturn(new GeneratedOutline("LOCAL_CODEX", renderedBase));

        var result = service.generate(projectId, new GenerateOutlineRequest(
                ModelProvider.LOCAL_CODEX, "微调", GenerationMode.REVISE, older.getId()));

        assertThat(result.baseOutlineVersionId()).isEqualTo(older.getId());
        verify(workflow).generate(projectId, renderedBible, budget, ModelProvider.LOCAL_CODEX,
                renderedBase, "微调", standardPolicy());
    }

    @Test
    void rejectsBaseVersionWhenRegenerating() {
        assertThatThrownBy(() -> service.generate(projectId, new GenerateOutlineRequest(
                ModelProvider.LOCAL_TEMPLATE, null, GenerationMode.REGENERATE, UUID.randomUUID())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能指定基准");
        verify(workflow, never()).generate(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void rejectsAnotherProjectsOutlineBeforeCallingModel() {
        UUID unavailableId = UUID.randomUUID();
        when(outlines.findFirstByProjectIdOrderByGenerationNumberDesc(projectId))
                .thenReturn(Optional.of(version(3, "最新草稿")));

        assertThatThrownBy(() -> service.generate(projectId, new GenerateOutlineRequest(
                ModelProvider.LOCAL_TEMPLATE, null, GenerationMode.REVISE, unavailableId)))
                .isInstanceOf(OutlineVersionNotFoundException.class);
        verify(workflow, never()).generate(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void restoringPublishedHistoricalVersionChangesCurrentPointerWithoutReplacingLatestDraft() {
        UUID userId = actor.currentUserId();
        NovelProject project = NovelProject.create(projectId, userId, "测试小说",
                com.novelagent.project.domain.EntryMode.IDEA);
        OutlineVersion older = version(1, "想恢复的旧版");
        older.publish();
        OutlineVersion latest = version(3, "较新的草稿");
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(outlines.findByIdAndProjectId(older.getId(), projectId)).thenReturn(Optional.of(older));
        when(outlines.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)).thenReturn(Optional.of(latest));
        when(outlines.saveAndFlush(older)).thenReturn(older);

        service.publish(projectId, older.getId(), older.getRowVersion());

        assertThat(service.current(projectId).orElseThrow().id()).isEqualTo(older.getId());
        assertThat(service.latest(projectId).orElseThrow().id()).isEqualTo(latest.getId());
    }

    private OutlineVersion version(int number, String title) {
        return OutlineVersion.create(UUID.randomUUID(), projectId, number, "LOCAL_TEMPLATE", null,
                bibleId, budget, content(title));
    }

    private static CreativeStrategyPolicy standardPolicy() {
        return CreativeStrategyPolicy.of(CreativeStrategy.STANDARD);
    }

    private static OutlineContent content(String title) {
        ChapterPlan chapter = new ChapterPlan(1, "开端", "主角", "目标", "事件", "揭示", "钩子", 2_400, 3_600);
        OutlineArc arc = new OutlineArc(1, "第一卷", "目标", "冲突", "转折", "结果",
                50_000, 70_000, List.of(chapter));
        return new OutlineContent(title, "前提", "结构", "节奏", 110_000, 130_000, List.of(arc));
    }

    private static StoryBibleContent bibleContent() {
        return new StoryBibleContent("故事", "主题", "世界", List.of(), "主角", "弧光",
                List.of(), List.of(), "冲突", "代价", "文风", "结局", List.of(), List.of());
    }
}
