package com.novelagent.modelaccess.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.agent.application.GenerationStoppedException;
import com.novelagent.modelaccess.application.ChatGptAccessException;
import com.novelagent.modelaccess.application.ChatGptOAuthService;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Text-only Responses adapter. Partial output remains a preview, never a completed model result. */
@Component
public class ChatGptResponsesClient {
    private final ChatGptHttp http;
    private final ChatGptOAuthService auth;
    private final ObjectMapper json;
    private final long idleNanos;
    private final URI endpoint;

    @Autowired
    public ChatGptResponsesClient(ChatGptHttp http, ChatGptOAuthService auth, ObjectMapper json,
            @Value("${app.ai.codex.turn-timeout-seconds:1200}") long idleSeconds) {
        this(http, auth, json, idleSeconds, ChatGptHttp.RESPONSES);
    }
    ChatGptResponsesClient(ChatGptHttp http, ChatGptOAuthService auth, ObjectMapper json, long idleSeconds, URI endpoint) {
        this.http = http; this.auth = auth; this.json = json;
        this.idleNanos = Duration.ofSeconds(Math.max(1, idleSeconds)).toNanos(); this.endpoint = endpoint;
    }

    public JsonNode models() { return http.get(ChatGptHttp.MODELS, auth.authorization().accessToken()); }

    public Result request(String instructions, JsonNode input, AgentRunRecorder.EffectiveSettings settings,
            String binding, Consumer<String> progress) {
        return request(instructions, input, settings, binding, progress, () -> { });
    }
    public Result request(String instructions, JsonNode input, AgentRunRecorder.EffectiveSettings settings,
            String binding, Consumer<String> progress, Runnable activityObserver) {
        var credentials = auth.authorization();
        if (!binding.equals(accountBinding(credentials)))
            throw new ChatGptAccessException("AUTHENTICATION", "账号在请求前发生变化，未发送旧会话资料，请重新生成");
        var payload = json.createObjectNode().put("model", settings.model()).put("instructions", instructions)
                .put("store", false).put("stream", true);
        payload.set("input", input);
        payload.putObject("reasoning").put("effort", settings.effort());
        payload.putArray("include").add("reasoning.encrypted_content");
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + credentials.accessToken())
                .header("Accept", "text/event-stream").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload.toString(), StandardCharsets.UTF_8)).build();
        var pending = http.client().sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
        InputStream body = null;
        CompletableFuture<Result> reading = new CompletableFuture<>();
        Thread reader = null;
        try {
            var response = pending.get(45, TimeUnit.SECONDS);
            body = response.body();
            boolean success = response.statusCode() >= 200 && response.statusCode() < 300;
            if (success && !response.headers().firstValue("Content-Type").orElse("").toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream"))
                throw new ChatGptAccessException("PROVIDER", "ChatGPT 未返回事件流，未保存响应；请检查代理或接口");
            AtomicLong activity = new AtomicLong(System.nanoTime());
            InputStream stream = body;
            reader = Thread.ofVirtual().name("chatgpt-response-reader").start(() -> {
                try {
                    if (!success) throw ChatGptHttp.failure(response.statusCode(),
                            new String(stream.readNBytes(16384), StandardCharsets.UTF_8),
                            response.headers().firstValue("x-request-id").orElse(""));
                    reading.complete(read(stream, progress, activity, activityObserver));
                }
                catch (Throwable error) { reading.completeExceptionally(error); }
            });
            while (true) {
                try { return reading.get(250, TimeUnit.MILLISECONDS); }
                catch (TimeoutException waiting) {
                    if (System.nanoTime() - activity.get() >= idleNanos)
                        throw new ChatGptAccessException("TIMEOUT", "ChatGPT 连续无新进展，空闲等待超时；未完成响应不会保存");
                }
            }
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt(); throw new GenerationStoppedException();
        } catch (java.util.concurrent.ExecutionException error) {
            if (error.getCause() instanceof RuntimeException failure) throw failure;
            throw new ChatGptAccessException("NETWORK", "ChatGPT network 流连接中断，未获得完成响应");
        } catch (TimeoutException error) {
            throw new ChatGptAccessException("TIMEOUT", "ChatGPT HTTP 连接或响应头等待超时");
        } finally {
            pending.cancel(true);
            if (body != null) try { body.close(); } catch (java.io.IOException ignored) { }
            if (reader != null) reader.interrupt();
        }
    }

    Result read(InputStream stream, Consumer<String> progress, AtomicLong activity) throws java.io.IOException {
        return read(stream, progress, activity, () -> { });
    }
    private Result read(InputStream stream, Consumer<String> progress, AtomicLong activity, Runnable activityObserver) throws java.io.IOException {
        var text = new StringBuilder();
        var event = new StringBuilder();
        long highestOutputTokens = 0;
        // Bound a single frame before parsing; no provider text is written to ordinary logs.
        try (var reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = boundedLine(reader)) != null) {
                if (!line.isEmpty()) {
                    if (line.startsWith("data:")) {
                        if (!event.isEmpty()) event.append('\n');
                        event.append(line.substring(5).stripLeading());
                        if (event.length() > 8_000_000) throw new ChatGptAccessException("PROVIDER", "ChatGPT 事件帧超过安全读取上限");
                    }
                    continue;
                }
                if (event.isEmpty()) continue;
                String data = event.toString(); event.setLength(0);
                if ("[DONE]".equals(data)) break;
                JsonNode message;
                try { message = json.readTree(data); }
                catch (com.fasterxml.jackson.core.JsonProcessingException error) {
                    throw new ChatGptAccessException("PROVIDER", "ChatGPT 返回无法解析的事件帧，未保存响应");
                }
                String type = message.path("type").asText();
                String delta = message.path("delta").asText();
                if ("response.output_text.delta".equals(type)) {
                    if (!delta.isEmpty()) {
                        activity.set(System.nanoTime()); activityObserver.run(); text.append(delta);
                        if (text.length() > 2_000_000) throw new ChatGptAccessException("PROVIDER", "ChatGPT 输出超过安全读取上限");
                        progress.accept(text.toString());
                    }
                } else if (type.startsWith("response.reasoning") && type.endsWith(".delta") && !delta.isEmpty()) {
                    activity.set(System.nanoTime()); // Reasoning must not enter the public preview.
                    activityObserver.run();
                }
                long tokens = message.at("/response/usage/output_tokens").asLong(0);
                if (tokens > highestOutputTokens) { activity.set(System.nanoTime()); activityObserver.run(); highestOutputTokens = tokens; }
                if ("response.failed".equals(type) || "error".equals(type))
                    throw ChatGptHttp.failure(200, message.has("response") ? message.get("response").toString() : data, "");
                if ("response.incomplete".equals(type))
                    throw new ChatGptAccessException("PROVIDER", "ChatGPT 返回未完成响应，未保存为有效结果");
                if ("response.completed".equals(type)) {
                    JsonNode response = message.path("response");
                    if (!"completed".equals(response.path("status").asText()) || !response.path("output").isArray())
                        throw new ChatGptAccessException("PROVIDER", "ChatGPT 完成事件缺少有效结果");
                    StringBuilder finalText = new StringBuilder();
                    for (var item : response.path("output")) {
                        if (!"message".equals(item.path("type").asText()) || !"assistant".equals(item.path("role").asText())) continue;
                        for (var part : item.path("content")) if ("output_text".equals(part.path("type").asText())) finalText.append(part.path("text").asText());
                    }
                    if (finalText.isEmpty()) throw new ChatGptAccessException("PROVIDER", "ChatGPT 未返回可用文本，可能拒绝了本次请求");
                    if (finalText.length() > 2_000_000) throw new ChatGptAccessException("PROVIDER", "ChatGPT 输出超过安全读取上限");
                    progress.accept(finalText.toString());
                    return new Result(response.path("id").asText(), finalText.toString(),
                            AgentRunRecorder.Usage.from(response.path("usage"), false), response.path("output").deepCopy());
                }
            }
        }
        throw new ChatGptAccessException("NETWORK", "ChatGPT network 流提前结束，未收到 response.completed；部分文字仅作预览");
    }
    private static String boundedLine(BufferedReader reader) throws java.io.IOException {
        StringBuilder line = new StringBuilder();
        int value;
        while ((value = reader.read()) != -1) {
            if (value == '\n') break;
            if (value != '\r') line.append((char) value);
            if (line.length() > 8_000_000) throw new ChatGptAccessException("PROVIDER", "ChatGPT 事件行超过安全读取上限");
        }
        return value == -1 && line.isEmpty() ? null : line.toString();
    }
    public static String accountBinding(PrivateOAuthStore.Credentials credentials) {
        return com.novelagent.platform.support.Sha256.ofUtf8(credentials.clientId() + "\n" + credentials.subject());
    }
    public record Result(String responseId, String output, AgentRunRecorder.Usage usage, JsonNode historyOutput)
            implements AgentRunRecorder.ModelResult { }
}
