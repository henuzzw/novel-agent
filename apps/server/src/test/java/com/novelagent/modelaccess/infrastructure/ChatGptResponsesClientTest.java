package com.novelagent.modelaccess.infrastructure;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.agent.application.GenerationStoppedException;
import com.novelagent.modelaccess.application.ChatGptOAuthService;
import com.novelagent.planning.application.ModelProvider;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ChatGptResponsesClientTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ChatGptOAuthService auth = mock(ChatGptOAuthService.class);
    private final ChatGptHttp http = new ChatGptHttp(json, "");
    private final PrivateOAuthStore.Credentials credentials = new PrivateOAuthStore.Credentials(
            "oaiapp_test", "subject", null, null, "test-access", "test-refresh", Long.MAX_VALUE, "chatgpt.tokens.use.direct");
    private final EffectiveSettings settings = new EffectiveSettings(ModelProvider.LOCAL_CODEX, "test-model", "high", 7L);
    private ChatGptResponsesClient client(URI endpoint, int idle) {
        when(auth.authorization()).thenReturn(credentials);
        return new ChatGptResponsesClient(http, auth, json, idle, endpoint);
    }
    private static String event(String data) { return "data: " + data.replace("\n", "\ndata: ") + "\n\n"; }
    private static String completed(String body) {
        return event("""
                {"type":"response.completed","response":{"id":"response-test","status":"completed",
                "usage":{"input_tokens":22,"output_tokens":7},"output":[
                {"type":"reasoning","id":"reasoning-test","encrypted_content":"opaque-private"},
                {"type":"message","role":"assistant","content":[{"type":"output_text","text":"%s"}]}]}}
                """.formatted(body));
    }
    @Test void consecutiveRealHttpRequestsCarryUpdatedInstructionsAndCompleteHistoryWithoutSchema() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new ArrayList<JsonNode>();
        var bearer = new ArrayList<String>();
        server.createContext("/responses", exchange -> {
            requests.add(json.readTree(exchange.getRequestBody()));
            bearer.add(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] bytes = completed("chapter").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (var body = exchange.getResponseBody()) { body.write(bytes); }
        });
        server.start();
        try {
            var client = client(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"), 2);
            var input = json.createArrayNode(); input.addObject().put("role", "user").put("content", "first stage");
            var first = client.request("bible instructions", input, settings, ChatGptResponsesClient.accountBinding(credentials), ignored -> {});
            first.historyOutput().forEach(input::add);
            input.addObject().put("role", "user").put("content", "second stage");
            client.request("outline instructions", input, settings, ChatGptResponsesClient.accountBinding(credentials), ignored -> {});
            assertThat(requests).hasSize(2);
            assertThat(bearer).containsOnly("Bearer test-access");
            assertThat(requests.get(0).path("instructions").asText()).isEqualTo("bible instructions");
            assertThat(requests.get(1).path("instructions").asText()).isEqualTo("outline instructions");
            assertThat(requests.get(1).path("input").size()).isEqualTo(4);
            assertThat(requests.get(1).at("/input/1/encrypted_content").asText()).isEqualTo("opaque-private");
            for (var request : requests) {
                assertThat(request.path("store").asBoolean()).isFalse();
                assertThat(request.path("stream").asBoolean()).isTrue();
                assertThat(request.at("/reasoning/effort").asText()).isEqualTo("high");
                assertThat(request.has("text") || request.has("max_output_tokens") || request.has("previous_response_id")
                        || request.has("conversation") || request.has("response_format")).isFalse();
                request.path("input").forEach(item -> assertThat(item.path("role").asText()).isNotEqualTo("system"));
            }
        } finally { server.stop(0); }
    }
    @Test void previewsOnlyPublicTextAndRequiresCompletedResult() throws Exception {
        var client = client(URI.create("http://127.0.0.1/unused"), 1);
        var progress = new ArrayList<String>(); var activity = new AtomicLong(0);
        String stream = event("{\"type\":\"response.reasoning_text.delta\",\"delta\":\"private-thought\"}")
                + event("{\"type\":\"response.output_text.delta\",\"delta\":\"partial\"}") + completed("final");
        var result = client.read(new ByteArrayInputStream(stream.getBytes(StandardCharsets.UTF_8)), progress::add, activity);
        assertThat(progress).containsExactly("partial", "final");
        assertThat(result.output()).isEqualTo("final");
        assertThat(result.usage()).isNotNull(); assertThat(activity.get()).isPositive();
        for (String ending : List.of("", event("[DONE]"), event("{\"type\":\"response.incomplete\"}"))) {
            String partial = event("{\"type\":\"response.output_text.delta\",\"delta\":\"preview\"}") + ending;
            assertThatThrownBy(() -> client.read(new ByteArrayInputStream(partial.getBytes(StandardCharsets.UTF_8)), ignored -> {}, new AtomicLong()))
                    .hasMessageContaining(ending.contains("incomplete") ? "未完成" : "未收到 response.completed");
        }
    }
    @Test void errorDetailsRetainSafeIdentifiersNotProviderEcho() {
        var error = ChatGptHttp.failure(429, "{\"error\":{\"code\":\"usage_limit_reached\",\"message\":\"private manuscript test-access\"}}", "req-test");
        assertThat(error.category()).isEqualTo("USAGE_LIMIT");
        assertThat(error.getMessage()).contains("usage_limit_reached", "req-test").doesNotContain("private manuscript", "test-access");
    }
    @Test void interruptStopsHttpWaitAndHeartbeatCannotKeepIdleStreamAlive() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        var started = new CountDownLatch(1);
        server.createContext("/responses", exchange -> {
            exchange.getRequestBody().readAllBytes(); exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0); started.countDown();
            try (var out = exchange.getResponseBody()) {
                for (int i = 0; i < 40; i++) { out.write(": heartbeat\n\n".getBytes(StandardCharsets.UTF_8)); out.flush(); Thread.sleep(100); }
            } catch (Exception ignored) { }
        });
        server.start();
        try {
            var client = client(URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/responses"), 1);
            long start = System.nanoTime();
            assertThatThrownBy(() -> client.request("test", json.createArrayNode(), settings,
                    ChatGptResponsesClient.accountBinding(credentials), ignored -> {})).hasMessageContaining("空闲等待超时");
            assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(3);
            var failure = new java.util.concurrent.atomic.AtomicReference<Throwable>();
            var worker = Thread.ofVirtual().start(() -> {
                try { client.request("test", json.createArrayNode(), settings, ChatGptResponsesClient.accountBinding(credentials), ignored -> {}); }
                catch (Throwable error) { failure.set(error); }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue(); worker.interrupt(); worker.join(3000);
            assertThat(worker.isAlive()).isFalse(); assertThat(failure.get()).isInstanceOf(GenerationStoppedException.class);
        } finally { server.stop(0); executor.shutdownNow(); }
    }
}
