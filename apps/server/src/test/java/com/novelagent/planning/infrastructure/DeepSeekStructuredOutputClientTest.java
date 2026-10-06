package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.application.GlobalModelSettingsService;
import com.novelagent.project.domain.GlobalModelSettings;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

class DeepSeekStructuredOutputClientTest {
    @Test void stopInterruptsRealHttpWaitWithoutWaitingForTheProviderResponse() throws Exception {
        var http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        http.createContext("/responses", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                entered.countDown();
                release.await(10, java.util.concurrent.TimeUnit.SECONDS);
                exchange.sendResponseHeaders(200, 0);
            } catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        http.start();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var controls = new com.novelagent.agent.application.GenerationControlRegistry();
        var jdbc = mock(org.springframework.jdbc.core.JdbcTemplate.class);
        var runId = new java.util.concurrent.atomic.AtomicReference<java.util.UUID>();
        org.mockito.Mockito.doAnswer(call -> { runId.set((java.util.UUID) ((Object[]) call.getRawArguments()[1])[0]); return 1; })
                .when(jdbc).update(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("INSERT INTO")), org.mockito.ArgumentMatchers.any(Object[].class));
        var recorder = new com.novelagent.agent.application.AgentRunRecorder(jdbc, java.math.BigDecimal.ZERO,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO,
                new com.novelagent.agent.application.AgentRunOutputBuffer(), controls);
        var client = new DeepSeekStructuredOutputClient("test-only-key", "http://127.0.0.1:" + http.getAddress().getPort(),
                mock(GlobalModelSettingsService.class));
        var settings = new com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings(ModelProvider.DEEPSEEK, "test", "none", 0L);
        java.util.UUID project = java.util.UUID.randomUUID();
        try {
            var future = executor.submit(() -> recorder.record(project, "OUTLINE", ModelProvider.DEEPSEEK,
                    "system", "input", null, () -> client.request("schema", "rules", "input", new ObjectMapper().createObjectNode(), 20, settings)));
            assertThat(entered.await(4, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            controls.stopRun(project, runId.get());
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> future.get(4, java.util.concurrent.TimeUnit.SECONDS))
                    .hasCauseInstanceOf(com.novelagent.agent.application.GenerationStoppedException.class);
            var values = org.mockito.ArgumentCaptor.forClass(Object[].class);
            org.mockito.Mockito.verify(jdbc).update(org.mockito.ArgumentMatchers.argThat(sql -> sql.contains("UPDATE agent_run")), values.capture());
            assertThat(values.getValue()[0]).isEqualTo("CANCELLED");
            assertThat(executor.submit(() -> Thread.currentThread().isInterrupted()).get()).isFalse();
        } finally { release.countDown(); http.stop(0); executor.shutdownNow(); }
    }
    @Test void everyRequestUsesTheSavedGlobalDeepSeekModel() {
        var builder = RestClient.builder().baseUrl("https://example.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        var settings = mock(GlobalModelSettingsService.class);
        when(settings.get()).thenReturn(
                new GlobalModelSettings(ModelProvider.LOCAL_CODEX, "gpt-6-sol", "high", "deepseek-flash", 1),
                new GlobalModelSettings(ModelProvider.LOCAL_CODEX, "gpt-6-sol", "high", "deepseek-v4-pro", 2));
        var client = new DeepSeekStructuredOutputClient("test-only-key", builder.build(), settings);
        String response = """
                {"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{}"}]}]}
                """;
        for (String model : java.util.List.of("deepseek-flash", "deepseek-v4-pro")) {
            server.expect(requestTo("https://example.invalid/responses")).andExpect(jsonPath("$.model").value(model))
                    .andExpect(jsonPath("$.reasoning.effort").value("none"))
                    .andRespond(withSuccess(response, MediaType.APPLICATION_JSON));
        }
        assertThat(client.request("test", "rules", "input", new ObjectMapper().createObjectNode(), 10)).isEqualTo("{}");
        assertThat(client.request("test", "rules", "input", new ObjectMapper().createObjectNode(), 10)).isEqualTo("{}");
        server.verify();
    }

    @Test void frozenRequestDoesNotReadSettingsAndReturnsProviderUsage() {
        var builder = RestClient.builder().baseUrl("https://example.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        var settings = mock(GlobalModelSettingsService.class);
        var client = new DeepSeekStructuredOutputClient("test-only-key", builder.build(), settings);
        server.expect(requestTo("https://example.invalid/responses"))
                .andExpect(jsonPath("$.model").value("deepseek-v4-pro"))
                .andExpect(jsonPath("$.max_output_tokens").value(20))
                .andRespond(withSuccess("""
                        {"status":"completed","usage":{"input_tokens":100,"output_tokens":10,"total_tokens":110,
                        "input_tokens_details":{"cached_tokens":30},"output_tokens_details":{"reasoning_tokens":2}},
                        "output":[{"type":"message","content":[{"type":"output_text","text":"{}"}]}]}
                        """, MediaType.APPLICATION_JSON));
        var result = client.request("schema", "rules", "input", new ObjectMapper().createObjectNode(), 20,
                new com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings(
                        ModelProvider.DEEPSEEK, "deepseek-v4-pro", "none", 2L));
        assertThat(result.output()).isEqualTo("{}");
        assertThat(result.usage().inputTokens()).isEqualTo(100);
        assertThat(result.usage().cachedInputTokens()).isEqualTo(30);
        assertThat(result.usage().reasoningOutputTokens()).isEqualTo(2);
        org.mockito.Mockito.verifyNoInteractions(settings);
        server.verify();
    }

    @Test void missingUsageDoesNotInventActualCounts() {
        var builder = RestClient.builder().baseUrl("https://example.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new DeepSeekStructuredOutputClient("test-only-key", builder.build(), mock(GlobalModelSettingsService.class));
        server.expect(requestTo("https://example.invalid/responses")).andRespond(withSuccess("""
                {"status":"completed","output":[{"type":"message","content":[{"type":"output_text","text":"{}"}]}]}
                """, MediaType.APPLICATION_JSON));
        assertThat(client.request("schema", "rules", "input", new ObjectMapper().createObjectNode(), 20,
                new com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings(
                        ModelProvider.DEEPSEEK, "deepseek-flash", "none", 2L)).usage()).isNull();
        server.verify();
    }

    @Test void authenticationFailureIsClassifiedAndNeverRetried() {
        var builder = RestClient.builder().baseUrl("https://example.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new DeepSeekStructuredOutputClient("test-only-key", builder.build(), mock(GlobalModelSettingsService.class));
        server.expect(requestTo("https://example.invalid/responses"))
                .andRespond(org.springframework.test.web.client.response.MockRestResponseCreators
                        .withStatus(org.springframework.http.HttpStatus.UNAUTHORIZED)
                        .body("private manuscript Bearer sensitive-value"));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.request("schema", "rules", "input",
                new ObjectMapper().createObjectNode(), 20,
                new com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings(
                        ModelProvider.DEEPSEEK, "deepseek-flash", "none", 2L)))
                .isInstanceOf(com.novelagent.planning.application.ModelProviderException.class)
                .hasMessageContaining("AUTHENTICATION")
                .hasMessageNotContaining("private manuscript").hasMessageNotContaining("sensitive-value");
        server.verify();
    }

    @Test void incompleteResponsePreservesReceivedUsage() {
        var builder = RestClient.builder().baseUrl("https://example.invalid");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client = new DeepSeekStructuredOutputClient("test-only-key", builder.build(), mock(GlobalModelSettingsService.class));
        server.expect(requestTo("https://example.invalid/responses")).andRespond(withSuccess("""
                {"status":"incomplete","usage":{"input_tokens":20,"output_tokens":5,"total_tokens":25},
                "incomplete_details":{"reason":"private manuscript"}}
                """, MediaType.APPLICATION_JSON));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.request("schema", "rules", "input",
                new ObjectMapper().createObjectNode(), 20,
                new com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings(
                        ModelProvider.DEEPSEEK, "deepseek-flash", "none", 2L)))
                .satisfies(error -> assertThat(((com.novelagent.agent.application.AgentRunRecorder.UsageCarrier) error)
                        .usage().outputTokens()).isEqualTo(5))
                .hasMessageNotContaining("private manuscript");
        server.verify();
    }
}
