package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.ingest.application.ImportAnalysisStore;
import com.novelagent.ingest.application.ImportedBibleGenerator;
import com.novelagent.ingest.infrastructure.ImportedPlanningModelGateway;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.planning.application.GeneratedStoryBible;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ImportedBibleContextBudgetTest {
    @Test void completeNotesAppearOnceInTheFinalBudgetedPromptAndAreNotRemovedFromTheSavedBaseline() {
        var models = mock(ImportedPlanningModelGateway.class);
        var parser = mock(StoryBibleModelOutputParser.class);
        var mapper = new ObjectMapper();
        var schema = new StoryBibleOutputSchema(mapper);
        var generator = new ImportedBibleGenerator(mock(ImportAnalysisStore.class), models, schema, parser);
        var input = mapper.createObjectNode().put("sourceImportId", UUID.randomUUID().toString())
                .put("sourceAnalysisId", UUID.randomUUID().toString()).put("sourceAnalysisVersion", 1)
                .put("mode", "ADAPT_SOURCE").put("sourceText", "作者提供的故事原文，不得丢弃".repeat(80));
        input.put("confirmedAnalysis", "已确认解析、逐项处理与逐字原文依据".repeat(600));
        String notes = "完整设计不得截断".repeat(6000);
        var baseline = new StoryBibleContent("基准核心", "主题", "世界", List.of(), "主角", "人物弧光", List.of(),
                List.of(), "冲突", "代价", "风格", "结局", List.of("作者硬约束"), List.of()).withDevelopmentNotes(notes);
        var direction = new StoryDirectionCandidate(UUID.randomUUID(), "方向", "前提", "冲突", "弧光", "结构", "结局",
                "读者", List.of(), List.of(), List.of());
        when(parser.parse(any(), anyString())).thenReturn(new GeneratedStoryBible("LOCAL_CODEX", baseline));
        when(models.request(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any()))
                .thenAnswer(call -> { Consumer<String> consumer = call.getArgument(8); consumer.accept("parsed fixture"); return "parsed fixture"; });
        generator.generate(UUID.randomUUID(), ModelProvider.LOCAL_CODEX, input, direction, notes, "仅调整节奏", baseline);
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(models).request(any(), eq("IMPORT_REVERSE_BIBLE"), any(), anyString(), prompt.capture(), any(), anyString(), eq(12000), any());
        assertThat(prompt.getValue()).containsOnlyOnce(notes).contains("作者硬约束", "仅调整节奏", "作者提供的故事原文，不得丢弃");
        assertThat(baseline.developmentNotes()).isEqualTo(notes);
        var protocol = new PlainTextOutputProtocol(schema.value());
        String wireUser = "【本轮服务端执行规范】\n" + PlainTextOutputProtocol.withoutLegacyProtocol(
                com.novelagent.prompt.application.AgentPromptDefaults.system("IMPORT_REVERSE_BIBLE_ADAPT"))
                + protocol.instructions() + "\n【本轮创作资料】\n" + prompt.getValue();
        String wireSystem = com.novelagent.prompt.application.AgentPromptDefaults.sessionSystemPrompt()
                + com.novelagent.prompt.application.AgentPromptService.protectedRules("IMPORT_REVERSE_BIBLE");
        var budget = new StructuredRequestBudget(new ModelContextProperties()).requireCapacity(ModelProvider.LOCAL_CODEX,
                wireSystem, wireUser, com.fasterxml.jackson.databind.node.NullNode.getInstance(), 12000);
        assertThat(budget.estimatedInputTokens()).isLessThan(112000);
        assertThat(baseline.withoutRepeatedDevelopmentNotes("作者新修改的底稿")).isSameAs(baseline);
    }
}
