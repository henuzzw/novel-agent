package com.novelagent.modelaccess.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static com.novelagent.writing.FirstThreeChaptersFixtures.source;

import com.novelagent.modelaccess.application.StructuredModelGateway;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.writing.domain.FirstThreeChaptersContent;
import com.novelagent.writing.infrastructure.FirstThreeChaptersModelAdapter;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class FirstThreeChaptersProtocolTest {
    private final StructuredModelGateway gateway = mock(StructuredModelGateway.class);
    private final FirstThreeChaptersModelAdapter model = new FirstThreeChaptersModelAdapter(
            gateway, new ModelContextProperties(), new ObjectMapper());

    private void respond(String text, Consumer<String> guide) {
        doAnswer(call -> {
            var protocol = new PlainTextOutputProtocol(call.getArgument(5, JsonNode.class));
            guide.accept(protocol.instructions());
            String output = protocol.decode(text);
            Consumer<String> process = call.getArgument(9);
            process.accept(output);
            return output;
        }).when(gateway).request(any(), anyString(), any(), anyString(), anyString(),
                any(), anyString(), anyInt(), any(), any());
    }

    private String report(boolean repeatDimension) {
        var result = new StringBuilder("## @summary\n完整三章范围\n");
        var dimensions = FirstThreeChaptersContent.Dimension.values();
        for (int index = 0; index < dimensions.length; index++) {
            String path = "assessments/" + (index + 1);
            result.append("## @").append(path).append("/dimension\n")
                    .append(repeatDimension ? "FIRST_CHAPTER" : dimensions[index].name())
                    .append("\n## @").append(path).append("/status\n")
                    .append(index == 0 ? "OBSERVATION" : "NOT_ASSESSED")
                    .append("\n## @").append(path).append("/observation\n定位观察\n");
            if (index == 0) result.append("## @").append(path).append("/evidence/1/chapterNumber\n1\n## @")
                    .append(path).append("/evidence/1/quote\n").append(quote()).append('\n');
            else result.append("## @").append(path).append("/evidence\n无\n");
        }
        return result.append("## @issues\n无").toString();
    }

    private String quote() { return source().chapters().getFirst().body().substring(0, 100).strip(); }

    @Test void actualOpeningSchemaGeneratesAndParsesStructuredEvidenceFromPureText() {
        respond(report(false), guide -> assertThat(guide).contains(
                "## @assessments/*/evidence/*/chapterNumber", "## @assessments/*/evidence/*/quote",
                "## @issues/*/evidence/*/chapterNumber", "FIRST_CHAPTER / CAUSAL_CONTINUITY"));
        var result = model.check(source(), ModelProvider.LOCAL_CODEX, "");
        assertThat(result.assessments()).hasSize(8);
        assertThat(result.assessments().getFirst().evidence().getFirst().quote()).isEqualTo(quote());
        assertThat(result.issues()).isEmpty();
    }

    @Test void duplicateDimensionsDoNotBecomeAValidReport() {
        respond(report(true), ignored -> { });
        assertThatThrownBy(() -> model.check(source(), ModelProvider.LOCAL_CODEX, ""))
                .hasMessageContaining("三章报告输出校验失败", "维度无效");
    }
}
