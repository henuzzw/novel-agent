package com.novelagent.writing.infrastructure;

import static com.novelagent.writing.FirstThreeChaptersFixtures.PROJECT;
import static com.novelagent.writing.FirstThreeChaptersFixtures.source;
import static com.novelagent.writing.FirstThreeChaptersFixtures.unassessed;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import com.novelagent.writing.domain.FirstThreeChaptersContent;
import com.novelagent.project.application.CreativeStrategyGuide;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class FirstThreeChaptersModelAdapterTest {
    private final StructuredModelGateway gateway = mock(StructuredModelGateway.class);
    private final ModelContextProperties capacities = new ModelContextProperties();
    private final ObjectMapper mapper = new ObjectMapper();
    private final FirstThreeChaptersModelAdapter adapter = new FirstThreeChaptersModelAdapter(gateway, capacities, mapper);
    @Test void completeBodiesAndDependenciesAreSentInOneNewThreadWithBoundedOutput() throws Exception {
        var source = source();
        when(gateway.request(any(), any(), any(), any(), any(), any(), any(), anyInt(), any()))
                .thenReturn(mapper.writeValueAsString(unassessed()));
        adapter.check(source, ModelProvider.LOCAL_CODEX, "author instruction");
        var input = ArgumentCaptor.forClass(String.class);
        var system = ArgumentCaptor.forClass(String.class);
        verify(gateway, times(1)).request(eq(PROJECT), eq("FIRST_THREE_CHAPTERS_REVIEW"), eq(ModelProvider.LOCAL_CODEX),
                system.capture(), input.capture(), any(), eq("opening_review_v1"), eq(6000), eq(CodexSessionPolicy.NEW_THREAD));
        assertThat(system.getValue()).contains(CreativeStrategyGuide.reviewRules());
        var json = mapper.readTree(input.getValue());
        for (int n = 0; n < 3; n++) assertThat(json.path("completeChapters").get(n).path("body").asText()).isEqualTo(source.chapters().get(n).body());
        assertThat(input.getValue()).contains("complete outline", "complete bible", "style", "profiles", "future secret", "author instruction");
        assertThat(json.path("completeChapters").get(0).path("contract").isObject()).isTrue();
        assertThat(adapter.budget(source, ModelProvider.LOCAL_CODEX, "author instruction").estimatedInputTokens()).isGreaterThan(20000);
    }
    @Test void configuredCapacityRejectsFullInputWithoutCallingOrTruncating() {
        capacities.getModels().get(ModelProvider.DEEPSEEK).setContextWindowTokens(15000);
        assertThat(adapter.budget(source(), ModelProvider.DEEPSEEK, "").fits()).isFalse();
        assertThatThrownBy(() -> adapter.check(source(), ModelProvider.DEEPSEEK, "")).hasMessageContaining("完整读取");
        verifyNoInteractions(gateway);
    }
    @Test void localTemplateNeverUsesGatewayOrPretendsToPass() {
        var result = adapter.check(source(), ModelProvider.LOCAL_TEMPLATE, "");
        assertThat(result.summary()).contains("未完成文学通读");
        assertThat(result.assessments()).allMatch(a -> a.status() == FirstThreeChaptersContent.Status.NOT_ASSESSED);
        assertThat(result.issues()).isEmpty();
        assertThat(adapter.budget(source(), ModelProvider.LOCAL_TEMPLATE, "").modelCalls()).isZero();
        verifyNoInteractions(gateway);
    }
    @Test void inventedEvidenceIsRejectedAtModelBoundary() throws Exception {
        var bad = new FirstThreeChaptersContent("范围", unassessed().assessments(), java.util.List.of(
                new FirstThreeChaptersContent.Issue("1", FirstThreeChaptersContent.Dimension.CHARACTER, "问题", "核对",
                        java.util.List.of(new FirstThreeChaptersContent.Evidence(1, "模型捏造的原文")))));
        when(gateway.request(any(), any(), any(), any(), any(), any(), any(), anyInt(), any())).thenReturn(mapper.writeValueAsString(bad));
        assertThatThrownBy(() -> adapter.check(source(), ModelProvider.DEEPSEEK, "")).hasMessageContaining("连续原文");
    }
}
