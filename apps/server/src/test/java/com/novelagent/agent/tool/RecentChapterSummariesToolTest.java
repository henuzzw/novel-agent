package com.novelagent.agent.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.memory.application.MemoryBudgetAllocator;
import com.novelagent.memory.application.MemoryBudgetPlan;
import com.novelagent.agent.application.AgentStage;
import com.novelagent.memory.application.NovelMemoryService;
import com.novelagent.planning.application.ModelProvider;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.sql.ResultSet;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class RecentChapterSummariesToolTest {
    @Test
    void readsOnlyThePreviousTwoChaptersAndPrefersActiveCanon() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        plan(jdbc, 1, 2, 3, 4, 5);
        UUID projectId = UUID.randomUUID();
        new RecentChapterSummariesTool(jdbc, new ObjectMapper()).execute(new NovelToolRequest(projectId, 4, 8,
                "当前章节", 10, 10));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Object[]> parameters = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(3)).query(sql.capture(), any(RowMapper.class), parameters.capture());
        assertThat(sql.getAllValues().getFirst()).contains("o.status = 'PUBLISHED'", "b.status = 'PUBLISHED'",
                "b.id = p.current_bible_version_id", "o.project_id = p.id", "b.project_id = p.id");
        assertThat(sql.getAllValues().subList(1, 3)).allSatisfy(query -> assertThat(query)
                .contains("c.active = TRUE", "m.status = 'AUTHOR_ACCEPTED'", "m.writing_basis->'plan'",
                        "c.accepted_facts::text",
                        "LEFT JOIN chapter_contract_version", "m.content->>'body' AS body",
                        "ORDER BY priority, version_number DESC", "m.project_id = c.project_id", "ct.project_id = m.project_id"));
        assertThat(parameters.getAllValues().get(1)).containsExactly(projectId, 3, 8L, projectId, 3);
        assertThat(parameters.getAllValues().get(2)).containsExactly(projectId, 2, 8L, projectId, 2);
    }

    @Test
    void firstChapterHasNoPreviousChapterQueries() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        plan(jdbc, 1);
        var result = new RecentChapterSummariesTool(jdbc, new ObjectMapper()).execute(new NovelToolRequest(
                UUID.randomUUID(), 1, 0, "第一章", 10, 10));
        assertThat(result.semanticMemories()).allMatch(NovelMemoryContext.SemanticMemory::futurePlan);
        assertThat(result.semanticMemories().getFirst().summary()).contains("末章，无下一章计划");
        verify(jdbc, times(1)).query(anyString(), any(RowMapper.class), any(Object[].class));
    }

    @Test
    void carriesAcceptedFactsOnlyFromActiveCanon() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        plan(jdbc, 1, 2, 3, 4);
        AtomicInteger calls = new AtomicInteger();
        when(jdbc.query(org.mockito.ArgumentMatchers.contains("SELECT chapter_number"), any(RowMapper.class), any(Object[].class))).thenAnswer(invocation -> {
            if (calls.getAndIncrement() > 0) return List.of();
            ResultSet row = mock(ResultSet.class);
            when(row.getInt("chapter_number")).thenReturn(2);
            when(row.getLong("canon_version")).thenReturn(3L);
            when(row.getString("summary")).thenReturn("前章摘要");
            when(row.getString("body")).thenReturn("前章正文");
            when(row.getString("contract_content")).thenReturn("{\"objective\":\"前章目标\"}");
            when(row.getString("manuscript_id")).thenReturn("manuscript-2");
            when(row.getString("contract_id")).thenReturn("contract-2");
            when(row.getString("commit_id")).thenReturn("commit-2");
            when(row.getInt("version_number")).thenReturn(5);
            when(row.getLong("manuscript_row_version")).thenReturn(7L);
            when(row.getString("accepted_facts")).thenReturn("""
                    [{"id":"F1","factType":"EVENT","subject":"主角","predicate":"得知",
                    "object":"真相","evidence":"纸条","decision":"ACCEPTED"}]
                    """);
            RowMapper<?> mapper = invocation.getArgument(1);
            return List.of(mapper.mapRow(row, 0));
        });

        var result = new RecentChapterSummariesTool(jdbc, new ObjectMapper()).execute(new NovelToolRequest(
                UUID.randomUUID(), 3, 3, "第三章", 10, 10));

        assertThat(result.semanticMemories()).hasSize(3);
        assertThat(result.semanticMemories().getFirst().content()).contains("前章目标", "前章正文");
        assertThat(result.semanticMemories().getFirst().chapterContract()).contains("前章目标");
        assertThat(result.semanticMemories().getFirst().chapterBody()).isEqualTo("前章正文");
        assertThat(result.graphFacts()).hasSize(1);
        assertThat(result.graphFacts().getFirst().object()).isEqualTo("真相");
        assertThat(result.semanticMemories().getFirst().summary()).contains("manuscript-2", "commit-2",
                "正文版本=5", "正文行版本=7", "内容指纹=");
        var allocated = new MemoryBudgetAllocator().allocate(result.semanticMemories(), result.graphFacts(),
                new MemoryBudgetPlan(AgentStage.CHAPTER_REVIEW, ModelProvider.LOCAL_TEMPLATE,
                        32000, 1000, 3000, 2000, 6000, 4000, 6000, 3, 10), List.of());
        assertThat(allocated.futureContext()).hasSize(1);
        assertThat(allocated.semanticMemories()).noneMatch(NovelMemoryContext.SemanticMemory::futurePlan);
        assertThat(allocated.toPromptText()).contains("未来规划边界", "第1章前文缺失", "manuscript-2");
        assertThat(allocated.usage().estimatedTokens()).isLessThanOrEqualTo(6000);
    }

    @Test
    void resolvesGapsAcrossArcsWithoutInventingChapters() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        plan(jdbc, 1, 4, 7, 10);
        var result = new RecentChapterSummariesTool(jdbc, new ObjectMapper()).execute(new NovelToolRequest(
                UUID.randomUUID(), 7, 4, "插叙章", 10, 10));
        assertThat(result.semanticMemories()).extracting(NovelMemoryContext.SemanticMemory::chapterNumber)
                .containsExactly(4, 1, 10);
        assertThat(result.semanticMemories().getLast().content()).contains("未来计划", "未来事件10");
        assertThat(result.graphFacts()).isEmpty();
    }

    @Test
    void futureBoundaryReachesContractManuscriptAndReviewThroughExistingRecall() throws Exception {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        plan(jdbc, 1, 4, 7, 10);
        AgentToolPolicy policy = mock(AgentToolPolicy.class);
        for (AgentStage stage : List.of(AgentStage.CHAPTER_CONTRACT, AgentStage.MANUSCRIPT, AgentStage.CHAPTER_REVIEW)) {
            when(policy.toolsFor(stage)).thenReturn(List.of(NovelToolName.GET_RECENT_CHAPTER_SUMMARIES));
        }
        var memory = new NovelMemoryService(new AgentToolOrchestrator(policy,
                List.of(new RecentChapterSummariesTool(jdbc, new ObjectMapper())), new MemoryBudgetAllocator()));
        for (AgentStage stage : List.of(AgentStage.CHAPTER_CONTRACT, AgentStage.MANUSCRIPT, AgentStage.CHAPTER_REVIEW)) {
            var recalled = memory.recall(stage, UUID.randomUUID(), 7, 3, "插叙",
                    new MemoryBudgetPlan(stage, ModelProvider.LOCAL_TEMPLATE, 32000, 1000, 3000, 2000,
                            6000, 4000, 6000, 3, 10));
            assertThat(recalled.semanticMemories()).extracting(NovelMemoryContext.SemanticMemory::chapterNumber)
                    .containsExactly(4, 1);
            assertThat(recalled.futureContext().getFirst().chapterNumber()).isEqualTo(10);
            assertThat(recalled.toPromptText()).contains("未来事件10", "来源项目=", "大纲内容指纹=");
            assertThat(recalled.graphFacts()).isEmpty();
            assertThat(recalled.usage().toolsUsed()).containsExactly("GET_RECENT_CHAPTER_SUMMARIES");
        }
    }

    @Test
    void authorAcceptedAndContractFallbackNeverContributeCanonFacts() throws Exception {
        for (long canonVersion : List.of(0L, -1L)) {
            JdbcTemplate jdbc = mock(JdbcTemplate.class);
            plan(jdbc, 1, 2);
            when(jdbc.query(org.mockito.ArgumentMatchers.contains("SELECT chapter_number"), any(RowMapper.class), any(Object[].class)))
                    .thenAnswer(invocation -> {
                        ResultSet row = mock(ResultSet.class);
                        when(row.getInt("chapter_number")).thenReturn(1);
                        when(row.getLong("canon_version")).thenReturn(canonVersion);
                        when(row.getString("summary")).thenReturn("前章来源降级");
                        when(row.getString("contract_content")).thenReturn("已确认合同");
                        when(row.getString("body")).thenReturn(canonVersion == 0 ? "未提交正文" : "");
                        when(row.getString("accepted_facts")).thenReturn("[]");
                        return List.of(((RowMapper<?>) invocation.getArgument(1)).mapRow(row, 0));
                    });
            var result = new RecentChapterSummariesTool(jdbc, new ObjectMapper()).execute(new NovelToolRequest(
                    UUID.randomUUID(), 2, 0, "第二章", 10, 10));
            var allocated = new MemoryBudgetAllocator().allocate(result.semanticMemories(), result.graphFacts(),
                    new MemoryBudgetPlan(AgentStage.MANUSCRIPT, ModelProvider.LOCAL_TEMPLATE,
                            32000, 1000, 3000, 2000, 6000, 4000, 6000, 3, 10), List.of());
            assertThat(allocated.graphFacts()).isEmpty();
            assertThat(allocated.toPromptText()).contains(canonVersion == 0 ? "作者已确认，未提交正史" : "仅合同已确认");
        }
    }

    private void plan(JdbcTemplate jdbc, int... numbers) throws Exception {
        var chapters = java.util.Arrays.stream(numbers).mapToObj(number -> new com.novelagent.planning.domain.ChapterPlan(
                number, "章" + number, "主角", "目标", "未来事件" + number, "揭示", "钩子", 2000, 3000)).toList();
        int split = Math.max(1, chapters.size() / 2);
        String outlineJson = new ObjectMapper().writeValueAsString(new com.novelagent.planning.domain.OutlineContent(
                "小说", "前提", "结构", "节奏", 4000, 6000, List.of(
                new com.novelagent.planning.domain.OutlineArc(1, "第一卷", "目标", "冲突", "转折", "结果", 2000, 3000,
                        chapters.subList(0, split)),
                new com.novelagent.planning.domain.OutlineArc(2, "第二卷", "目标", "冲突", "转折", "结果", 2000, 3000,
                        chapters.subList(split, chapters.size())))));
        when(jdbc.query(org.mockito.ArgumentMatchers.contains("SELECT o.id"), any(RowMapper.class), any(Object[].class)))
                .thenAnswer(invocation -> {
                    ResultSet row = mock(ResultSet.class);
                    when(row.getString("outline_id")).thenReturn("outline-1");
                    when(row.getString("bible_id")).thenReturn("bible-1");
                    when(row.getString("outline_content")).thenReturn(outlineJson);
                    return List.of(((RowMapper<?>) invocation.getArgument(1)).mapRow(row, 0));
                });
    }
}
