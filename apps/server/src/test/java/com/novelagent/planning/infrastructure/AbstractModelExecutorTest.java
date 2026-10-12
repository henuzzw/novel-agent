package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.agent.application.AgentRunRecorder.ModelResult;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.planning.application.ModelProvider;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class AbstractModelExecutorTest {
    @Test void successAcceptsAfterSaveAndEveryFailureClosesWithoutAccepting() throws Exception {
        for (String failure : List.of("none", "budget", "send", "decode", "save")) {
            var events = new ArrayList<String>();
            var runs = mock(AgentRunRecorder.class);
            when(runs.record(any(), anyString(), any(), anyString(), anyString(), any(), any(), any())).thenAnswer(call -> {
                events.add("record");
                var result = ((Supplier<?>) call.getArgument(6)).get();
                ((Consumer<Object>) call.getArgument(7)).accept(result);
                return result;
            });
            var context = new ModelContextProperties();
            if (failure.equals("budget")) context.getModels().put(ModelProvider.DEEPSEEK, new ModelContextProperties.Capacity(1, 0));
            var executor = new AbstractModelExecutor(runs, context) {
                @Override public Entry entry() { return Entry.DEEPSEEK; }
                @Override protected EffectiveSettings effectiveSettings() {
                    return new EffectiveSettings(ModelProvider.DEEPSEEK, "model", "none", 1L);
                }
                @Override protected PreparedCall prepare(Request request, EffectiveSettings settings) {
                    events.add("prepare");
                    return new PreparedCall() {
                        @Override public String system() { return request.system(); }
                        @Override public String input() { return request.user(); }
                        @Override public String policy() { return "STATELESS"; }
                        @Override public ModelResult send(Consumer<String> progress) {
                            events.add("send");
                            if (failure.equals("send")) throw new IllegalStateException("request failed");
                            return new DeepSeekStructuredOutputClient.ResponseResult(failure.equals("decode") ? "" : "text", null);
                        }
                        @Override public void accept(ModelResult result) { events.add("accept"); }
                        @Override public void close() { events.add("close"); }
                    };
                }
            };
            var protocol = new PlainTextOutputProtocol(new ObjectMapper().readTree("{\"type\":\"object\",\"properties\":{\"text\":{\"type\":\"string\"}}}"));
            var request = new AbstractModelExecutor.Request(UUID.randomUUID(), "OUTLINE", ModelProvider.DEEPSEEK,
                    "system", "input", "text", protocol, 100, CodexSessionPolicy.NEW_THREAD, null, false);
            Runnable invoke = () -> executor.execute(request, output -> {
                events.add("save");
                if (failure.equals("save")) throw new IllegalStateException("save failed");
            });
            if (failure.equals("none")) {
                invoke.run();
                assertThat(events).containsExactly("prepare", "record", "send", "save", "accept", "close");
            } else {
                assertThatThrownBy(invoke::run).isInstanceOf(RuntimeException.class);
                assertThat(events).endsWith("close").doesNotContain("accept");
                if (failure.equals("budget")) assertThat(events).containsExactly("prepare", "close");
            }
        }
    }
}
