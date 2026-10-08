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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.GenerationStoppedException;
import com.novelagent.planning.domain.SnowflakePlan;
import com.novelagent.planning.infrastructure.FreeTextPlanningRequest;
import com.novelagent.planning.infrastructure.SnowflakePlanStore;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.NovelProject;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class SnowflakePlanningServiceTest {
    private final UUID project = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final ObjectMapper mapper = new ObjectMapper();
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final SnowflakePlanStore store = mock(SnowflakePlanStore.class);
    private final FreeTextPlanningRequest texts = mock(FreeTextPlanningRequest.class);
    private final CharacterDesignService characters = mock(CharacterDesignService.class);
    private final SnowflakePlanningService service = new SnowflakePlanningService(access, store, texts, characters);

    @BeforeEach void setup() {
        when(access.requireOwnedProject(project)).thenReturn(mock(NovelProject.class));
        when(store.create(eq(project), eq(ModelProvider.DEEPSEEK), any())).thenReturn(id);
        when(texts.request(eq(project), anyString(), eq(ModelProvider.DEEPSEEK), anyString(), eq("CORE"), anyInt()))
                .thenReturn("核心与梗概自由文本");
        when(characters.designText(eq(project), eq(ModelProvider.DEEPSEEK), anyString())).thenReturn("人物不按字段写也可以");
        when(texts.request(eq(project), anyString(), eq(ModelProvider.DEEPSEEK), anyString(), eq("WORLD"), anyInt()))
                .thenReturn("普通校园没有复杂设定");
        when(texts.request(eq(project), anyString(), eq(ModelProvider.DEEPSEEK), anyString(), eq("PLOT"), anyInt()))
                .thenReturn("顺着因果讲三幕，不必列满模板");
        when(store.get(project, id)).thenReturn(new SnowflakePlan(id, project, "NEW_STORY", ModelProvider.DEEPSEEK,
                "SUCCEEDED", "PLOT", "核心", "人物", "世界", "三幕", null, Instant.now(), Instant.now()));
    }

    @Test void runsSeriallySavesEachStageAndFeedsAllPredecessorsWithoutLiteraryValidation() {
        var input = mapper.createObjectNode().put("mode", "NEW_STORY").put("authorInstruction", "保留QQ相册开头");
        assertThat(service.generate(project, ModelProvider.DEEPSEEK, input).status()).isEqualTo("SUCCEEDED");
        var order = inOrder(store, texts, characters);
        order.verify(store).create(project, ModelProvider.DEEPSEEK, input);
        order.verify(store).start(project, id, "CORE");
        order.verify(texts).request(eq(project), eq("SNOWFLAKE_PLANNING"), any(), anyString(), eq("CORE"), eq(2500));
        order.verify(store).save(project, id, "CORE", "核心与梗概自由文本");
        order.verify(store).start(project, id, "CHARACTERS");
        order.verify(characters).designText(eq(project), any(), anyString());
        order.verify(store).save(project, id, "CHARACTERS", "人物不按字段写也可以");
        order.verify(store).start(project, id, "WORLD");
        var worldPrompt = ArgumentCaptor.forClass(String.class);
        order.verify(texts).request(eq(project), eq("SNOWFLAKE_PLANNING"), any(), worldPrompt.capture(), eq("WORLD"), eq(8000));
        assertThat(worldPrompt.getValue()).contains("保留QQ相册开头", "核心与梗概自由文本", "人物不按字段写也可以");
        order.verify(store).save(project, id, "WORLD", "普通校园没有复杂设定");
        order.verify(store).start(project, id, "PLOT");
        var plotPrompt = ArgumentCaptor.forClass(String.class);
        order.verify(texts).request(eq(project), anyString(), any(), plotPrompt.capture(), eq("PLOT"), eq(8000));
        assertThat(plotPrompt.getValue()).contains("核心与梗概自由文本", "人物不按字段写也可以", "普通校园没有复杂设定");
        order.verify(store).save(project, id, "PLOT", "顺着因果讲三幕，不必列满模板");
        order.verify(store).finish(project, id, "SUCCEEDED", null);
    }

    @Test void keepsPrecedingStagesAndStopsOnFailure() {
        when(characters.designText(eq(project), any(), anyString())).thenThrow(new IllegalArgumentException("人物请求失败"));
        assertThatThrownBy(() -> service.generate(project, ModelProvider.DEEPSEEK, mapper.createObjectNode()))
                .hasMessageContaining("人物请求失败");
        verify(store).save(project, id, "CORE", "核心与梗概自由文本");
        verify(store).finish(project, id, "FAILED", "人物请求失败");
        verify(texts, org.mockito.Mockito.never()).request(any(), anyString(), any(), anyString(), eq("WORLD"), anyInt());
    }

    @Test void cancellationCannotContinueToNextStage() {
        when(characters.designText(eq(project), any(), anyString())).thenThrow(new GenerationStoppedException());
        assertThatThrownBy(() -> service.generate(project, ModelProvider.DEEPSEEK, mapper.createObjectNode()))
                .isInstanceOf(GenerationStoppedException.class);
        verify(store).finish(eq(project), eq(id), eq("CANCELLED"), anyString());
        verify(texts, org.mockito.Mockito.never()).request(any(), anyString(), any(), anyString(), eq("PLOT"), anyInt());
    }

    @Test void templateAndUnauthorizedCallsDoNotCreatePlans() {
        assertThatThrownBy(() -> service.generate(project, ModelProvider.LOCAL_TEMPLATE, mapper.createObjectNode()))
                .hasMessageContaining("真实模型");
        verifyNoInteractions(store, texts, characters);
        when(access.requireOwnedProject(project)).thenThrow(new IllegalArgumentException("项目不属于当前用户"));
        assertThatThrownBy(() -> service.latest(project)).hasMessageContaining("当前用户");
        verifyNoInteractions(store);
    }

    @ParameterizedTest
    @ValueSource(strings = { "NEW_STORY", "ADAPT_SOURCE", "CONTINUE_MANUSCRIPT" })
    void characterStageReceivesExplicitModeRichDesignRulesAndFrozenSource(String mode) {
        var input = mapper.createObjectNode().put("mode", mode).put("authorInstruction", "保留老师称谓，不擅自改名")
                .put("sourceText", "她只在原文中被称作老师");
        service.generate(project, ModelProvider.DEEPSEEK, input);
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(characters).designText(eq(project), eq(ModelProvider.DEEPSEEK), prompt.capture());
        assertThat(prompt.getValue()).contains("【当前任务模式】\n" + mode, "保留老师称谓，不擅自改名",
                "她只在原文中被称作老师", "核心与梗概自由文本", CharacterBlueprintGuide.designRules());
        assertThat(input.path("mode").asText()).isEqualTo(mode);
        verify(store).save(project, id, "CHARACTERS", "人物不按字段写也可以");
    }
}
