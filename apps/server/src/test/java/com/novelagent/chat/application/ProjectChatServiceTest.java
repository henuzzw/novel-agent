package com.novelagent.chat.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.GenerationControlRegistry;
import com.novelagent.chat.domain.*;
import com.novelagent.memory.application.MemoryPreviewService;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.modelaccess.application.StructuredModelGateway;
import com.novelagent.project.application.GlobalModelSettingsService;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProjectChatServiceTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ProjectChatStore store = mock(ProjectChatStore.class);
    private final ChatMaterials materials = mock(ChatMaterials.class);
    private final StructuredModelGateway model = mock(StructuredModelGateway.class);
    private final MemoryPreviewService memory = mock(MemoryPreviewService.class);
    private final UUID project = UUID.randomUUID(), id = UUID.randomUUID();
    private final ChatSource source = new ChatSource("PROFILE:one:background", "林舟 · 背景", "已有背景", true,
            "fingerprint", "basis", "PROFILE", "one", "background", json.createObjectNode());
    private final ProjectChatService service = new ProjectChatService(store, materials, model, json,
            mock(GlobalModelSettingsService.class), memory, new GenerationControlRegistry(), Runnable::run);
    private void arrange(ChatAction... actions) {
        var turn = new ProjectChatStore.Turn(id, "调整人物背景", "", "RUNNING", null, json.createArrayNode(), Instant.now());
        when(store.active(project, id)).thenReturn(true);
        when(store.get(project, id)).thenReturn(turn);
        when(store.list(project)).thenReturn(List.of(turn));
        when(store.changes(project)).thenReturn(List.of());
        when(materials.catalog(project)).thenReturn(List.of(source));
        var queue = new ArrayDeque<>(List.of(actions));
        when(model.request(eq(project), eq("PROJECT_CHAT"), any(), anyString(), anyString(), any(), anyString(), anyInt(), any(), any()))
                .thenAnswer(call -> {
                    Consumer<String> output = call.getArgument(9);
                    output.accept(json.writeValueAsString(queue.removeFirst()));
                    return "unused";
                });
    }
    private ChatAction action(String type, String key, String replacement) {
        return new ChatAction(type, key, 0, "", replacement, "背景应支撑选择", "后续动机待核对", "候选待作者确认");
    }
    @Test void readsBeforeProposingAndOnlyStagesChangesWithoutApplying() {
        arrange(action("READ", source.key(), ""), action("PROPOSE", source.key(), "新的背景"), action("REPLY", "", ""));
        service.execute(project, id, ModelProvider.DEEPSEEK);
        var proposals = ArgumentCaptor.forClass(List.class);
        verify(store).complete(eq(project), eq(id), eq("候选待作者确认"), anyList(), proposals.capture());
        assertThat(proposals.getValue()).hasSize(1);
        verify(materials, never()).apply(any(), any(), any(), any());
        verify(store, never()).decide(any(), any(), any());
        verify(store, never()).fail(any(), any(), any(), any());
    }
    @Test void crossProjectUnknownTargetsCannotReadOrProduceChanges() {
        arrange(action("READ", "PROFILE:other-project:background", ""));
        service.execute(project, id, ModelProvider.LOCAL_CODEX);
        verify(store).fail(eq(project), eq(id), eq("FAILED"), contains("授权目录"));
        verify(store, never()).complete(any(), any(), any(), any(), any());
    }
    @Test void proposingWithoutFullReadOrCallingApplyToolFailsClosed() {
        arrange(action("PROPOSE", source.key(), "新内容"));
        service.execute(project, id, ModelProvider.DEEPSEEK);
        verify(store).fail(eq(project), eq(id), eq("FAILED"), contains("完整读取"));
        reset(store, model);
        arrange(action("APPLY", source.key(), "新内容"));
        service.execute(project, id, ModelProvider.DEEPSEEK);
        verify(store).fail(eq(project), eq(id), eq("FAILED"), contains("无应用"));
        verify(store, never()).complete(any(), any(), any(), any(), any());
    }
    @Test void stoppedTurnDoesNotRequestModelOrSaveLateOutput() {
        arrange(action("READ", source.key(), ""));
        when(store.active(project, id)).thenReturn(false);
        service.execute(project, id, ModelProvider.DEEPSEEK);
        verifyNoInteractions(model);
        verify(store).fail(eq(project), eq(id), eq("CANCELLED"), anyString());
    }
    @Test void emptySearchCanExplainMissingEvidenceWithoutInventingSources() {
        var empty = mock(NovelMemoryContext.class);
        when(empty.toPromptText()).thenReturn("没有检索命中");
        when(empty.semanticMemories()).thenReturn(List.of());
        when(empty.graphFacts()).thenReturn(List.of());
        when(memory.preview(eq(project), anyInt(), eq("未出现的人物"), any(), any())).thenReturn(empty);
        arrange(new ChatAction("SEARCH", "", 0, "未出现的人物", "", "", "", ""), action("REPLY", "", ""));
        service.execute(project, id, ModelProvider.DEEPSEEK);
        verify(store).complete(eq(project), eq(id), anyString(), eq(List.of()), eq(List.of()));
        verify(store, never()).fail(any(), any(), any(), any());
    }
}
