package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.ModelInputUnavailableException;
import com.novelagent.planning.application.ModelProvider;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

class FreeTextPlanningRequestTest {
    private final StructuredModelGateway models = mock(StructuredModelGateway.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final FreeTextPlanningRequest texts = new FreeTextPlanningRequest(models, mapper);

    @Test void arbitraryProseDoesNotNeedArcWorldOrThreeActFields() throws Exception {
        String prose = "人物的变化只写一段也可以。\n不必凑齐五个节点。";
        stub(mapper.createObjectNode().put("text", prose).toString());
        assertThat(texts.request(UUID.randomUUID(), "SNOWFLAKE_PLANNING", ModelProvider.DEEPSEEK, "来源", "PLOT", 8000))
                .isEqualTo(prose);
    }

    @Test void malformedOrEmptyTransportIsRejectedInsideRecordedCallback() {
        for (String raw : new String[] { "not json", "{}", "{\"text\":\" \"}", "{\"text\":123}" }) {
            stub(raw);
            assertThatThrownBy(() -> texts.request(UUID.randomUUID(), "SNOWFLAKE_PLANNING", ModelProvider.DEEPSEEK,
                    "来源", "WORLD", 8000)).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test void nonEmptyInputFailureIsRejectedInsideRecordedCallback() {
        stub(mapper.createObjectNode().put("text", "当前环境没有可用的本地文件读取工具，无法读取指定完整来源。").toString());
        assertThatThrownBy(() -> texts.request(UUID.randomUUID(), "SNOWFLAKE_PLANNING", ModelProvider.DEEPSEEK,
                "来源", "CORE", 8000)).isInstanceOf(ModelInputUnavailableException.class);
    }

    private void stub(String output) {
        Mockito.reset(models);
        when(models.requestWithBoundaries(any(), anyString(), any(), anyString(), anyString(), any(), anyString(), anyInt(), any(), any(),
                ArgumentMatchers.nullable(String.class)))
                .thenAnswer(call -> {
                    JsonNode schema = call.getArgument(5);
                    assertThat(schema.path("properties").size()).isEqualTo(1);
                    assertThat(schema.path("properties").has("text")).isTrue();
                    Consumer<String> callback = call.getArgument(9);
                    callback.accept(output);
                    return output;
                });
    }
}
