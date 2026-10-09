package com.novelagent.ingest.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.ingest.api.ReversePlanRequest;
import com.novelagent.ingest.domain.ImportPlanningMode;
import com.novelagent.ingest.infrastructure.ImportedPlanningModelGateway;
import com.novelagent.planning.application.GeneratedOutline;
import com.novelagent.planning.application.GeneratedStoryBible;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.ChapterPlanStatus;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineWordBudgetPolicy;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.infrastructure.OutlineModelOutputParser;
import com.novelagent.planning.infrastructure.OutlineOutputSchema;
import com.novelagent.planning.infrastructure.StoryBibleModelOutputParser;
import com.novelagent.planning.infrastructure.StoryBibleOutputSchema;
import com.novelagent.project.application.CreativeStrategyGuide;
import com.novelagent.project.application.CreativeStrategyService;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.infrastructure.CreativeIntentRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class ImportedPlanningServiceTest {
    @ParameterizedTest
    @EnumSource(ImportPlanningMode.class)
    void importPlanningReceivesSelectedStrategyWithoutRewritingOccurredChapters(ImportPlanningMode mode) {
        var project = UUID.randomUUID();
        var importId = UUID.randomUUID();
        var analysisId = UUID.randomUUID();
        var mapper = new ObjectMapper();
        var imports = mock(WorkImportService.class);
        var models = mock(ImportedPlanningModelGateway.class);
        var bibleParser = mock(StoryBibleModelOutputParser.class);
        var outlineParser = mock(OutlineModelOutputParser.class);
        var intents = mock(CreativeIntentRepository.class);
        var analyses = mock(ImportAnalysisStore.class);
        var strategies = mock(CreativeStrategyService.class);
        var drafts = mock(ImportedPlanningDraftStore.class);
        var characters = mock(com.novelagent.planning.application.SnowflakePlanningService.class);
        var plan = new com.novelagent.planning.domain.SnowflakePlan(UUID.randomUUID(), project, mode.name(),
                ModelProvider.DEEPSEEK, "SUCCEEDED", "PLOT", "核心", "江澈自由文本人物设计", "世界", "三幕自由文本", null,
                java.time.Instant.now(), java.time.Instant.now());
        when(characters.generate(eq(project), any(), any())).thenReturn(plan);
        var service = new ImportedPlanningService(imports, models, new StoryBibleOutputSchema(mapper), bibleParser,
                new OutlineOutputSchema(mapper), outlineParser, new OutlineWordBudgetPolicy(), intents, drafts,
                mock(JdbcTemplate.class), mapper, analyses, strategies, characters);
        String guide = CreativeStrategyGuide.render(CreativeStrategyPolicy.of(CreativeStrategy.FANQIE_GRIPPING));
        when(strategies.promptContext(project)).thenReturn(guide);
        when(analyses.requireConfirmed(project, importId, analysisId, 1L, mode)).thenReturn(mapper.createObjectNode());
        when(imports.planningSource(project, importId)).thenReturn(new WorkImportService.PlanningSource("原文内容", 1, 4, false));
        when(intents.findById(project)).thenReturn(Optional.empty());
        when(models.request(eq(project), eq("IMPORT_REVERSE_BIBLE"), eq(ModelProvider.DEEPSEEK),
                anyString(), anyString(), any(), eq("imported_story_bible"), eq(10000))).thenReturn("bible");
        when(models.request(eq(project), eq("IMPORT_REVERSE_OUTLINE"), eq(ModelProvider.DEEPSEEK),
                anyString(), anyString(), any(), eq("imported_outline"), eq(16000))).thenReturn("outline");
        var bible = new GeneratedStoryBible("TEST", new StoryBibleContent("故事", "主题", "世界", List.of(),
                "主角", "弧光", List.of(), List.of(), "冲突", "代价", "风格", "结局", List.of(), List.of()));
        when(bibleParser.parse(ModelProvider.DEEPSEEK, "bible")).thenReturn(bible);
        when(outlineParser.parse(ModelProvider.DEEPSEEK, "outline")).thenReturn(outline(3));

        service.generate(project, importId, new ReversePlanRequest(ModelProvider.DEEPSEEK, mode, null, analysisId, 1L));

        var biblePrompt = ArgumentCaptor.forClass(String.class);
        var outlinePrompt = ArgumentCaptor.forClass(String.class);
        verify(models).request(eq(project), eq("IMPORT_REVERSE_BIBLE"), eq(ModelProvider.DEEPSEEK),
                anyString(), biblePrompt.capture(), any(), eq("imported_story_bible"), eq(10000));
        verify(models).request(eq(project), eq("IMPORT_REVERSE_OUTLINE"), eq(ModelProvider.DEEPSEEK),
                anyString(), outlinePrompt.capture(), any(), eq("imported_outline"), eq(16000));
        var order = org.mockito.Mockito.inOrder(characters, models);
        order.verify(characters).generate(eq(project), eq(ModelProvider.DEEPSEEK), any());
        order.verify(models).request(eq(project), eq("IMPORT_REVERSE_BIBLE"), any(), anyString(), anyString(), any(), anyString(), eq(10000));
        order.verify(models).request(eq(project), eq("IMPORT_REVERSE_OUTLINE"), any(), anyString(), anyString(), any(), anyString(), eq(16000));
        assertThat(biblePrompt.getValue()).contains(guide, mode.name());
        assertThat(outlinePrompt.getValue()).contains(guide, "江澈");
        var saved = ArgumentCaptor.forClass(GeneratedOutline.class);
        verify(drafts).save(eq(project), eq(importId), eq(mode), eq(null), eq(new GeneratedStoryBible(bible.generatorType(), bible.content().withDevelopmentNotes(plan.context()), bible.changeSummary())), any(), saved.capture(),
                eq(analysisId), eq(1L));
        assertThat(saved.getValue().content().arcs().getFirst().chapters().getFirst().status())
                .isEqualTo(mode == ImportPlanningMode.CONTINUE_MANUSCRIPT ? ChapterPlanStatus.OCCURRED : ChapterPlanStatus.PLANNED);
    }

    @Test
    void stopsBeforeReverseBibleAndRecordsFailureWhenCharacterDesignFails() {
        UUID project = UUID.randomUUID(), imported = UUID.randomUUID(), analysis = UUID.randomUUID();
        var mapper = new ObjectMapper();
        var imports = mock(WorkImportService.class);
        var models = mock(ImportedPlanningModelGateway.class);
        var drafts = mock(ImportedPlanningDraftStore.class);
        var jdbc = mock(JdbcTemplate.class);
        var analyses = mock(ImportAnalysisStore.class);
        var strategies = mock(CreativeStrategyService.class);
        var characters = mock(com.novelagent.planning.application.SnowflakePlanningService.class);
        var service = new ImportedPlanningService(imports, models, new StoryBibleOutputSchema(mapper),
                mock(StoryBibleModelOutputParser.class), new OutlineOutputSchema(mapper), mock(OutlineModelOutputParser.class),
                new OutlineWordBudgetPolicy(), mock(CreativeIntentRepository.class), drafts, jdbc, mapper, analyses, strategies, characters);
        when(analyses.requireConfirmed(project, imported, analysis, 1L, ImportPlanningMode.ADAPT_SOURCE)).thenReturn(mapper.createObjectNode());
        when(imports.planningSource(project, imported)).thenReturn(new WorkImportService.PlanningSource("已确认原文", 1, 5, false));
        when(characters.generate(eq(project), eq(ModelProvider.DEEPSEEK), any())).thenThrow(new IllegalArgumentException("人物设计输出格式不合法"));
        assertThatThrownBy(() -> service.generate(project, imported,
                new ReversePlanRequest(ModelProvider.DEEPSEEK, ImportPlanningMode.ADAPT_SOURCE, null, analysis, 1L)))
                .hasMessageContaining("人物设计");
        org.mockito.Mockito.verifyNoInteractions(models, drafts);
        verify(jdbc).update(org.mockito.ArgumentMatchers.contains("planning_status = 'FAILED'"),
                org.mockito.ArgumentMatchers.contains("人物设计"), eq(imported));
    }

    @Test
    void deterministicallySeparatesOccurredAndPlannedChapters() {
        GeneratedOutline normalized = ImportedPlanningService.normalizeChapterStatuses(outline(3), 2);

        assertThat(normalized.content().arcs().getFirst().chapters())
                .extracting(ChapterPlan::status)
                .containsExactly(ChapterPlanStatus.OCCURRED, ChapterPlanStatus.OCCURRED,
                        ChapterPlanStatus.PLANNED);
        assertThat(normalized.content().arcs().getFirst().chapters())
                .extracting(ChapterPlan::sceneOutline).containsOnly("逐场说明目标、阻力与行动后果");
        assertThat(normalized.content().arcs().getFirst().chapters())
                .extracting(ChapterPlan::sceneOutlineNeedsUpdate).containsOnly(false);
        assertThat(ImportedPlanningService.normalizeChapterStatuses(outline(3), 0)
                .content().arcs().getFirst().chapters())
                .extracting(ChapterPlan::status)
                .containsOnly(ChapterPlanStatus.PLANNED);
        assertThatThrownBy(() -> ImportedPlanningService.normalizeChapterStatuses(outline(1), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("少于已导入章节数");
    }

    private static GeneratedOutline outline(int chapterCount) {
        List<ChapterPlan> chapters = java.util.stream.IntStream.rangeClosed(1, chapterCount)
                .mapToObj(number -> new ChapterPlan(number, "第" + number + "章", "主角", "目标", "事件",
                        "揭示", "钩子", 2_000, 4_000, ChapterPlanStatus.PLANNED).withSceneOutline("逐场说明目标、阻力与行动后果"))
                .toList();
        OutlineArc arc = new OutlineArc(1, "第一卷", "目标", "冲突", "转折", "结果",
                20_000, 40_000, chapters);
        return new GeneratedOutline("TEST", new OutlineContent("书名", "前提", "结构", "节奏",
                50_000, 60_000, List.of(arc)));
    }
}
