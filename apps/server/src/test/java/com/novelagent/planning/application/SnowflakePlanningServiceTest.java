package com.novelagent.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.novelagent.modelaccess.domain.ModelProvider;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.agent.application.GenerationStoppedException;
import com.novelagent.agent.application.ModelInputUnavailableException;
import com.novelagent.planning.domain.SnowflakePlan;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.modelaccess.application.FreeTextModelRequest;
import com.novelagent.planning.infrastructure.SnowflakePlanStore;
import com.novelagent.project.application.CreativeBoundaryGuide;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.CreativeBoundaries;
import com.novelagent.project.domain.NovelProject;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

class SnowflakePlanningServiceTest {
    private final UUID project = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final StoryBibleContent materials = mapper.convertValue(
            Map.ofEntries(
                    Map.entry("logline", "共同参赛"), Map.entry("theme", "合作"),
                    Map.entry("worldSetting", "校园"), Map.entry("worldRules", List.of("现实校园")),
                    Map.entry("protagonist", "陆舟"), Map.entry("protagonistArc", "学会回应"),
                    Map.entry("supportingCharacters", List.of()), Map.entry("relationshipDynamics", List.of()),
                    Map.entry("centralConflict", "分工冲突"), Map.entry("stakes", "信任"),
                    Map.entry("narrativeStyle", "清楚"), Map.entry("endingDirection", "共同选择"),
                    Map.entry("hardConstraints", List.of()), Map.entry("openQuestions", List.of()),
                    Map.entry("characterBlueprints", List.of(Map.of(
                            "name", "陆舟", "role", "PROTAGONIST", "identity", "学生", "coreDesire", "参赛")))),
            StoryBibleContent.class);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final SnowflakePlanStore store = mock(SnowflakePlanStore.class);
    private final FreeTextModelRequest texts = mock(FreeTextModelRequest.class);
    private final CharacterDesignService characters = mock(CharacterDesignService.class);
    private final SnowflakePlanningService service = new SnowflakePlanningService(access, store,
            new SnowflakeStageGenerator(texts, characters, mapper));

    @BeforeEach void setup() {
        when(access.requireOwnedProject(project)).thenReturn(mock(NovelProject.class));
        when(store.create(eq(project), eq(ModelProvider.DEEPSEEK), any())).thenReturn(id);
        when(texts.request(eq(project), anyString(), eq(ModelProvider.DEEPSEEK), anyString(), eq("CORE"), anyInt(), anyString()))
                .thenReturn("CORE底稿");
        when(characters.designPlanning(eq(project), eq(ModelProvider.DEEPSEEK), anyString(), anyString())).thenReturn(materials);
        when(texts.request(eq(project), anyString(), eq(ModelProvider.DEEPSEEK), anyString(), anyString(), anyInt(), anyString()))
                .thenAnswer(call -> call.getArgument(4) + "底稿");
        when(store.get(project, id)).thenReturn(new SnowflakePlan(id, project, "NEW_STORY", ModelProvider.DEEPSEEK,
                "SUCCEEDED", "PLOT", "核心", "人物", "世界", "三幕", null, Instant.now(), Instant.now()));
    }

    @Test void runsSeriallySavesEachStageAndFeedsAllPredecessorsWithoutLiteraryValidation() {
        var input = mapper.createObjectNode().put("mode", "NEW_STORY").put("authorInstruction", "保留QQ相册开头");
        assertThat(service.generate(project, ModelProvider.DEEPSEEK, input).status()).isEqualTo("SUCCEEDED");
        var order = inOrder(store, texts, characters);
        var frozen = ArgumentCaptor.forClass(JsonNode.class);
        order.verify(store).create(eq(project), eq(ModelProvider.DEEPSEEK), frozen.capture());
        assertThat(frozen.getValue().path("authorInstruction")).isEqualTo(input.path("authorInstruction"));
        String boundaries = frozen.getValue().path("creativeBoundarySnapshot").asText();
        assertThat(input.has("creativeBoundarySnapshot")).isFalse();
        for (var step : SnowflakeStep.values()) {
            if (step == SnowflakeStep.SCENE_EXPANSION) continue;
            order.verify(store).start(project, id, step.name());
            if (step == SnowflakeStep.CHARACTER_SETTINGS) {
                var captured = ArgumentCaptor.forClass(String.class);
                order.verify(characters).designPlanning(eq(project), any(), captured.capture(), eq(boundaries));
                assertThat(captured.getValue()).contains("保留QQ相册开头", "CORE底稿", "CHARACTER_BIOGRAPHIES底稿");
                order.verify(store).saveMaterials(eq(project), eq(id), anyString(), eq(mapper.valueToTree(materials)));
            } else {
                order.verify(texts).request(eq(project), eq("SNOWFLAKE_PLANNING"), any(), anyString(), eq(step.name()), eq(step.maxTokens()), eq(boundaries));
                order.verify(store).save(project, id, step.name(), step.name() + "底稿");
            }
        }
        verify(texts, Mockito.never()).request(any(), anyString(), any(), anyString(), eq("SCENE_EXPANSION"), anyInt(), anyString());
        order.verify(store).finish(project, id, "SUCCEEDED", null);
    }

    @Test void keepsPrecedingStagesAndStopsOnFailure() {
        when(characters.designPlanning(eq(project), any(), anyString(), anyString())).thenThrow(new IllegalArgumentException("人物请求失败"));
        assertThatThrownBy(() -> service.generate(project, ModelProvider.DEEPSEEK, mapper.createObjectNode()))
                .hasMessageContaining("人物请求失败");
        verify(store).save(project, id, "CORE", "CORE底稿");
        verify(store).finish(project, id, "FAILED", "人物请求失败");
        verify(texts, Mockito.never()).request(any(), anyString(), any(), anyString(), eq("SCENE_LIST"), anyInt(), anyString());
    }

    @Test void cancellationCannotContinueToNextStage() {
        when(characters.designPlanning(eq(project), any(), anyString(), anyString())).thenThrow(new GenerationStoppedException());
        assertThatThrownBy(() -> service.generate(project, ModelProvider.DEEPSEEK, mapper.createObjectNode()))
                .isInstanceOf(GenerationStoppedException.class);
        verify(store).finish(eq(project), eq(id), eq("CANCELLED"), anyString());
        verify(texts, Mockito.never()).request(any(), anyString(), any(), anyString(), eq("SCENE_LIST"), anyInt(), anyString());
    }

    @Test void templateAndUnauthorizedCallsDoNotCreatePlans() {
        assertThatThrownBy(() -> service.generate(project, ModelProvider.LOCAL_TEMPLATE, mapper.createObjectNode()))
                .hasMessageContaining("真实模型");
        verifyNoInteractions(store, texts, characters);
        when(access.requireOwnedProject(project)).thenThrow(new IllegalArgumentException("项目不属于当前用户"));
        assertThatThrownBy(() -> service.latest(project)).hasMessageContaining("当前用户");
        verifyNoInteractions(store);
    }

    private ObjectNode resumeInput(boolean expand) {
        var projectValue = access.requireOwnedProject(project);
        var input = mapper.createObjectNode().put("mode", "ADAPT_SOURCE").put("expandScenes", expand)
                .put("authorInstruction", "原要求");
        input.put("creativeBoundarySnapshot", CreativeBoundaryGuide.render(
                CreativeBoundaries.from(projectValue)));
        when(store.input(project, id)).thenReturn(input);
        return input;
    }

    private void stopped(Map<String, String> steps) {
        when(store.get(project, id)).thenReturn(new SnowflakePlan(id, project, "ADAPT_SOURCE", ModelProvider.DEEPSEEK,
                "CANCELLED", "CHARACTER_SETTINGS", "核心", null, null, null, "已停止", Instant.now(), Instant.now(), steps));
    }

    @Test void resumeReusesSixSavedStepsRequestsOnlyTheRemainingThreeAndRetainsTheSource() {
        var input = resumeInput(true);
        var steps = new LinkedHashMap<String, String>();
        for (var step : SnowflakeStep.values()) {
            if (step == SnowflakeStep.CHARACTER_SETTINGS) break;
            steps.put(step.name(), step.name() + "已存原稿");
        }
        stopped(steps);
        service.resume(project, id);
        verify(store).resume(project, id);
        verify(store, Mockito.never()).create(any(), any(), any());
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(characters).designPlanning(eq(project), eq(ModelProvider.DEEPSEEK), prompt.capture(), anyString());
        for (var entry : steps.entrySet()) {
            assertThat(prompt.getValue()).contains(entry.getValue());
            verify(store, Mockito.never()).start(project, id, entry.getKey());
            verify(store, Mockito.never()).save(eq(project), eq(id), eq(entry.getKey()), anyString());
        }
        assertThat(prompt.getValue()).contains("原要求");
        verify(texts, Mockito.times(2)).request(eq(project), anyString(), any(), anyString(), anyString(), anyInt(), anyString());
        verify(store).finish(project, id, "SUCCEEDED", null);
        assertThat(input.path("expandScenes").asBoolean()).isTrue();
    }

    @Test void optionalStageStaysDisabledAndAnotherStopPreservesPriorWork() {
        resumeInput(false);
        stopped(Map.of("CORE", "已有核心"));
        when(characters.designPlanning(eq(project), any(), anyString(), anyString())).thenThrow(new GenerationStoppedException());
        assertThatThrownBy(() -> service.resume(project, id)).isInstanceOf(GenerationStoppedException.class);
        verify(store).finish(eq(project), eq(id), eq("CANCELLED"), anyString());
        verify(store, Mockito.never()).save(eq(project), eq(id), eq("CORE"), anyString());
        verify(texts, Mockito.never()).request(any(), anyString(), any(), anyString(), eq("SCENE_EXPANSION"), anyInt(), anyString());
    }

    @Test void explicitResumeRestartsAtFirstUnreadableStageAndDiscardsItsDependentSuffix() {
        resumeInput(false);
        var previous = Map.of("CORE", "当前环境没有可用的本地文件读取工具，无法读取指定完整来源。",
                "SYNOPSIS", "看似成功但依赖无效CORE的梗概");
        stopped(previous);
        service.resume(project, id);
        verify(store).resumeWithValidPrefix(project, id, previous, Map.of());
        verify(store).start(project, id, "CORE");
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(characters).designPlanning(eq(project), any(), prompt.capture(), anyString());
        assertThat(prompt.getValue()).doesNotContain(previous.get("CORE"), previous.get("SYNOPSIS"));
        verify(store).finish(project, id, "SUCCEEDED", null);
    }

    @Test void normalPrefixIsKeptButNoAccessFailureCanStartAnotherStage() {
        resumeInput(false);
        var previous = Map.of("CORE", "真正的故事核心", "SYNOPSIS", "当前仍没有本地文件读取工具，不能生成梗概。");
        stopped(previous);
        when(texts.request(eq(project), anyString(), any(), anyString(), eq("SYNOPSIS"), anyInt(), anyString()))
                .thenReturn("当前仍没有本地文件读取工具，不能生成梗概。");
        assertThatThrownBy(() -> service.resume(project, id))
                .isInstanceOf(ModelInputUnavailableException.class);
        verify(store).resumeWithValidPrefix(project, id, previous, Map.of("CORE", "真正的故事核心"));
        verify(store, Mockito.never()).start(project, id, "CORE");
        verify(store, Mockito.never()).start(project, id, "CHARACTER_ARCS");
        verify(store, Mockito.never()).save(eq(project), eq(id), eq("SYNOPSIS"), anyString());
        verify(store).finish(eq(project), eq(id), eq("FAILED"), anyString());
    }

    @Test void changedBoundariesBrokenPrefixAndDuplicateClaimsCannotInvokeModels() {
        var input = resumeInput(false);
        stopped(Map.of("CORE", "已存核心"));
        input.put("creativeBoundarySnapshot", "已改变的世界设置");
        assertThatThrownBy(() -> service.resume(project, id)).hasMessageContaining("已改变");
        resumeInput(false);
        stopped(Map.of("SYNOPSIS", "缺少核心"));
        assertThatThrownBy(() -> service.resume(project, id)).hasMessageContaining("不连续");
        stopped(Map.of("CORE", "已存核心"));
        Mockito.doThrow(new IllegalStateException("状态已改变")).when(store).resume(project, id);
        assertThatThrownBy(() -> service.resume(project, id)).hasMessageContaining("状态已改变");
        verifyNoInteractions(texts, characters);
    }

    @Test void fullySavedPlanCanRetryDirectionsWithoutAnySnowflakeRequests() {
        resumeInput(false);
        var steps = new LinkedHashMap<String, String>();
        for (var step : SnowflakeStep.values()) {
            if (step != SnowflakeStep.SCENE_EXPANSION) steps.put(step.name(), "已存" + step.name());
        }
        var saved = new SnowflakePlan(id, project, "ADAPT_SOURCE", ModelProvider.DEEPSEEK,
                "SUCCEEDED", "SCENE_LIST", "核心", "人物", null, "情节", null, Instant.now(), Instant.now(), steps);
        when(store.get(project, id)).thenReturn(saved);
        assertThat(service.resume(project, id)).isSameAs(saved);
        verify(store, Mockito.never()).resume(any(), any());
        verifyNoInteractions(texts, characters);
    }

    @ParameterizedTest
    @ValueSource(strings = { "NEW_STORY", "ADAPT_SOURCE", "CONTINUE_MANUSCRIPT" })
    void characterStageReceivesExplicitModeRichDesignRulesAndFrozenSource(String mode) {
        var input = mapper.createObjectNode().put("mode", mode).put("authorInstruction", "保留老师称谓，不擅自改名")
                .put("sourceText", "她只在原文中被称作老师");
        service.generate(project, ModelProvider.DEEPSEEK, input);
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(characters).designPlanning(eq(project), eq(ModelProvider.DEEPSEEK), prompt.capture(), anyString());
        assertThat(prompt.getValue()).contains("【当前任务模式】\n" + mode, "保留老师称谓，不擅自改名",
                "她只在原文中被称作老师", "CORE底稿");
        assertThat(input.path("mode").asText()).isEqualTo(mode);
        verify(store).saveMaterials(eq(project), eq(id), anyString(), eq(mapper.valueToTree(materials)));
    }
}
