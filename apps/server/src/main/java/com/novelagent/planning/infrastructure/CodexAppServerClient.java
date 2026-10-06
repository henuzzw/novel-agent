package com.novelagent.planning.infrastructure;

import jakarta.annotation.PreDestroy;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.List;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import com.novelagent.project.application.GlobalModelSettingsService;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.agent.application.AgentRunRecorder.ModelResult;
import com.novelagent.agent.application.AgentRunRecorder.Usage;
import com.novelagent.agent.application.AgentRunRecorder.UsageCarrier;
import com.novelagent.planning.application.ModelProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

@Component
public class CodexAppServerClient {

    private static final Logger log = LoggerFactory.getLogger(CodexAppServerClient.class);
    private static final int STDERR_LINE_LIMIT = 40;
    private static final List<String> HOST_CODEX_VARIABLES = List.of(
            "CODEX_APP_TOOLS_PIPE_PATH",
            "CODEX_CI",
            "CODEX_INTERNAL_ORIGINATOR_OVERRIDE",
            "CODEX_MCP_NODE_PATH",
            "CODEX_PERMISSION_PROFILE",
            "CODEX_SANDBOX_NETWORK_DISABLED",
            "CODEX_SESSION_ID",
            "CODEX_SHELL",
            "CODEX_THREAD_ID",
            "CODEX_WINDOWS_SANDBOX_PACKAGE_FAMILY");
    private static final List<String> PROXY_VARIABLES = List.of(
            "ALL_PROXY", "HTTP_PROXY", "HTTPS_PROXY", "GIT_HTTP_PROXY", "GIT_HTTPS_PROXY");

    private final ObjectMapper objectMapper;
    private final String command;
    private final String model;
    private final String effort;
    private final GlobalModelSettingsService modelChoices;
    private final Duration timeout;
    private final Duration turnTimeout;
    private final Path runtimeRoot;
    private final Path codexHome;
    private final Path stateRoot;
    private final Path authSource;
    private final String proxyUrl;
    private final Object lifecycleMonitor = new Object();
    private final Object writeMonitor = new Object();
    private final AtomicLong requestSequence = new AtomicLong();
    private final Map<Long, CompletableFuture<JsonNode>> pendingRequests = new ConcurrentHashMap<>();
    private final Map<String, ActiveTurn> activeTurns = new ConcurrentHashMap<>();
    private final Set<String> attachedThreads = ConcurrentHashMap.newKeySet();
    private final Map<String, String> abandonedTurns = new ConcurrentHashMap<>();
    private final Deque<String> recentErrors = new ArrayDeque<>();

    private volatile Process process;
    private volatile BufferedWriter writer;

    @Autowired
    public CodexAppServerClient(
            ObjectMapper objectMapper,
            @Value("${app.ai.codex.command}") String command,
            @Value("${app.ai.codex.model}") String model,
            @Value("${app.ai.codex.effort}") String effort,
            @Value("${app.ai.codex.timeout-seconds}") long timeoutSeconds,
            @Value("${app.ai.codex.runtime-directory}") String runtimeDirectory,
            @Value("${app.ai.codex.auth-source}") String authSource,
            @Value("${app.ai.codex.proxy-url:}") String proxyUrl,
            GlobalModelSettingsService modelChoices,
            @Value("${app.ai.codex.turn-timeout-seconds:1200}") long turnTimeoutSeconds) {
        this.objectMapper = objectMapper;
        this.command = command;
        this.model = model;
        this.effort = effort;
        this.modelChoices = modelChoices;
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.turnTimeout = Duration.ofSeconds(turnTimeoutSeconds);
        if (timeoutSeconds <= 0 || turnTimeoutSeconds <= 0) throw new IllegalArgumentException("Codex 等待上限必须大于零");
        this.runtimeRoot = Path.of(runtimeDirectory).toAbsolutePath().normalize();
        this.codexHome = this.runtimeRoot.resolve("home");
        this.stateRoot = this.codexHome.resolve("state");
        this.authSource = Path.of(authSource).toAbsolutePath().normalize();
        this.proxyUrl = proxyUrl == null ? "" : proxyUrl.trim();
    }

    public CodexAppServerClient(ObjectMapper objectMapper, String command, String model, String effort,
            long timeoutSeconds, String runtimeDirectory, String authSource, String proxyUrl,
            GlobalModelSettingsService modelChoices) {
        this(objectMapper, command, model, effort, timeoutSeconds, runtimeDirectory, authSource, proxyUrl,
                modelChoices, timeoutSeconds);
    }

    CodexAppServerClient(ObjectMapper objectMapper, String command, String model, String effort,
            long timeoutSeconds, String runtimeDirectory, String authSource, String proxyUrl) {
        this(objectMapper, command, model, effort, timeoutSeconds, runtimeDirectory, authSource, proxyUrl, null);
    }

    EffectiveSettings effectiveSettings() {
        if (modelChoices == null) return new EffectiveSettings(ModelProvider.LOCAL_CODEX, model, effort, null);
        var settings = modelChoices.get();
        return new EffectiveSettings(ModelProvider.LOCAL_CODEX,
                settings.codexModel(), settings.codexEffort(), settings.version());
    }

    public JsonNode listModels() {
        ArrayNode models = objectMapper.createArrayNode();
        String cursor = null;
        do {
            ObjectNode params = objectMapper.createObjectNode();
            params.put("limit", 100);
            params.put("includeHidden", false);
            if (cursor != null) params.put("cursor", cursor);
            JsonNode result = request("model/list", params);
            for (JsonNode item : result.path("data")) models.add(item);
            cursor = result.path("nextCursor").asText(null);
        } while (cursor != null && !cursor.isBlank());
        return models;
    }

    public String startThread(UUID projectId, String developerInstructions) {
        return startThread(projectId, developerInstructions, effectiveSettings());
    }

    public String startThread(UUID projectId, String developerInstructions, EffectiveSettings choice) {
        Path workspace = prepareWorkspace(projectId);
        ObjectNode params = objectMapper.createObjectNode();
        params.put("model", choice.model());
        params.putObject("config").put("model_reasoning_effort", choice.effort());
        params.put("cwd", workspace.toString());
        params.put("serviceName", "novel-agent");
        params.put("approvalPolicy", "never");
        params.put("sandbox", "read-only");
        params.put("ephemeral", false);
        params.put("developerInstructions", developerInstructions);
        params.set("dynamicTools", objectMapper.createArrayNode());
        params.set("environments", objectMapper.createArrayNode());

        JsonNode result = request("thread/start", params);
        String threadId = requiredText(result, "/thread/id", "Codex 未返回 thread ID");
        attachedThreads.add(threadId);
        log.info("Codex thread started projectId={} threadId={} model={}", projectId, threadId, choice.model());
        return threadId;
    }

    public void resumeThread(String threadId, UUID projectId, String developerInstructions) {
        resumeThread(threadId, projectId, developerInstructions, effectiveSettings());
    }

    public void resumeThread(String threadId, UUID projectId, String developerInstructions, EffectiveSettings choice) {
        ensureStarted();
        if (attachedThreads.contains(threadId)) {
            return;
        }
        ObjectNode params = objectMapper.createObjectNode();
        params.put("threadId", threadId);
        params.put("model", choice.model());
        params.putObject("config").put("model_reasoning_effort", choice.effort());
        params.put("cwd", prepareWorkspace(projectId).toString());
        params.put("approvalPolicy", "never");
        params.put("sandbox", "read-only");
        params.put("developerInstructions", developerInstructions);
        params.put("excludeTurns", true);
        request("thread/resume", params);
        attachedThreads.add(threadId);
        log.info("Codex thread resumed projectId={} threadId={} model={}", projectId, threadId, choice.model());
    }

    public TurnResult runStructuredTurn(String threadId, UUID projectId, String prompt, JsonNode outputSchema) {
        return runStructuredTurn(threadId, projectId, prompt, outputSchema, effectiveSettings());
    }

    public TurnResult runStructuredTurn(String threadId, UUID projectId, String prompt,
            JsonNode outputSchema, EffectiveSettings choice) {
        return runStructuredTurn(threadId, projectId, prompt, outputSchema, choice, ignored -> { });
    }

    public TurnResult runStructuredTurn(String threadId, UUID projectId, String prompt,
            JsonNode outputSchema, EffectiveSettings choice, Consumer<String> progress) {
        if (abandonedTurns.containsKey(threadId)) {
            throw new CodexAppServerException("上一轮 Codex 尚未确认停止，不能复用该会话，请稍后重试");
        }
        ActiveTurn activeTurn = new ActiveTurn(progress == null ? ignored -> { } : progress);
        if (activeTurns.putIfAbsent(threadId, activeTurn) != null) {
            throw new CodexAppServerException("该小说项目的 Codex 会话正在生成，请稍后再试");
        }

        Instant startedAt = Instant.now();
        log.info("Codex turn starting projectId={} threadId={} model={} effort={} timeoutSeconds={}",
                projectId, threadId, choice.model(), choice.effort(), turnTimeout.toSeconds());
        try {
            ObjectNode params = structuredTurnParams(threadId, projectId, prompt, outputSchema, choice);
            JsonNode result = request("turn/start", params);
            activeTurn.turnId = requiredText(result, "/turn/id", "Codex 未返回 turn ID");
            synchronized (activeTurn) {
                for (var event : activeTurn.earlyEvents) handleTurnEvent(event.method(), event.params());
                activeTurn.earlyEvents.clear();
            }
            String output = await(activeTurn.completion, "等待 Codex 完成生成", turnTimeout);
            if (output == null || output.isBlank()) {
                throw new CodexAppServerException("Codex 已结束生成，但没有返回正文内容");
            }
            log.info("Codex turn completed projectId={} threadId={} turnId={} durationMs={}",
                    projectId, threadId, activeTurn.turnId, Duration.between(startedAt, Instant.now()).toMillis());
            return new TurnResult(activeTurn.turnId, output, activeTurn.currentUsage());
        }
        catch (RuntimeException exception) {
            if ((exception.getCause() instanceof TimeoutException
                    || exception.getCause() instanceof InterruptedException) && activeTurn.turnId != null) {
                boolean needsInterrupt;
                synchronized (activeTurn) {
                    needsInterrupt = !activeTurn.completion.isDone();
                    if (needsInterrupt) abandonedTurns.put(threadId, activeTurn.turnId);
                }
                if (needsInterrupt) {
                    boolean interrupted = Thread.interrupted();
                    try { interruptTimedOutTurn(threadId, activeTurn.turnId); }
                    finally { if (interrupted) Thread.currentThread().interrupt(); }
                }
            }
            log.warn("Codex turn failed projectId={} threadId={} turnId={} model={} durationMs={} category={}",
                    projectId, threadId, activeTurn.turnId, choice.model(),
                    Duration.between(startedAt, Instant.now()).toMillis(), failureCategory(exception.getMessage()));
            if (activeTurn.currentUsage() != null) {
                throw new TurnFailure(exception, activeTurn.currentUsage());
            }
            throw exception;
        }
        finally {
            activeTurns.remove(threadId, activeTurn);
        }
    }

    void interruptTimedOutTurn(String threadId, String turnId) {
        long id = requestSequence.incrementAndGet();
        var future = new CompletableFuture<JsonNode>();
        pendingRequests.put(id, future);
        try {
            var message = objectMapper.createObjectNode().put("id", id).put("method", "turn/interrupt");
            message.putObject("params").put("threadId", threadId).put("turnId", turnId);
            writeMessage(message);
            JsonNode response = future.get(2, TimeUnit.SECONDS);
            log.info("Codex timeout interrupt threadId={} turnId={} acknowledged={}", threadId, turnId,
                    !response.has("error"));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            log.warn("Codex timeout interrupt not confirmed threadId={} turnId={} exceptionType={}",
                    threadId, turnId, exception.getClass().getSimpleName());
        } finally {
            pendingRequests.remove(id);
        }
    }

    ObjectNode structuredTurnParams(String threadId, UUID projectId, String prompt, JsonNode outputSchema) {
        return structuredTurnParams(threadId, projectId, prompt, outputSchema, effectiveSettings());
    }

    ObjectNode structuredTurnParams(String threadId, UUID projectId, String prompt, JsonNode outputSchema,
            EffectiveSettings choice) {
        ObjectNode params = objectMapper.createObjectNode();
        params.put("threadId", threadId);
        params.put("model", choice.model());
        params.put("effort", choice.effort());
        params.put("cwd", prepareWorkspace(projectId).toString());
        params.put("approvalPolicy", "never");
        params.set("sandboxPolicy", readOnlySandbox());
        params.set("environments", objectMapper.createArrayNode());
        params.set("disabledPluginIds", objectMapper.createArrayNode());
        params.set("outputSchema", outputSchema);
        ArrayNode input = params.putArray("input");
        input.addObject().put("type", "text").put("text", prompt);
        return params;
    }

    JsonNode request(String method, JsonNode params) {
        ensureStarted();
        return requestOnStartedProcess(method, params);
    }

    private JsonNode requestOnStartedProcess(String method, JsonNode params) {
        long id = requestSequence.incrementAndGet();
        Instant startedAt = Instant.now();
        log.debug("Codex RPC starting method={} requestId={}", method, id);
        CompletableFuture<JsonNode> future = new CompletableFuture<>();
        pendingRequests.put(id, future);

        ObjectNode request = objectMapper.createObjectNode();
        request.put("id", id);
        request.put("method", method);
        request.set("params", params);
        try {
            writeMessage(request);
            JsonNode response = method.equals("turn/start") ? awaitTurnStart(future) : await(future, "调用 Codex " + method);
            JsonNode error = response.get("error");
            if (error != null && !error.isNull()) {
                Integer code = error.has("code") ? error.get("code").asInt() : null;
                String message = error.path("message").asText("未知 RPC 错误");
                log.warn("Codex RPC rejected method={} requestId={} code={} durationMs={} category={}",
                        method, id, code, Duration.between(startedAt, Instant.now()).toMillis(),
                        failureCategory(message));
                throw new CodexAppServerException("Codex " + method + " 失败：" + message, code);
            }
            log.debug("Codex RPC completed method={} requestId={} durationMs={}",
                    method, id, Duration.between(startedAt, Instant.now()).toMillis());
            return response.path("result");
        }
        finally {
            pendingRequests.remove(id);
        }
    }

    private void ensureStarted() {
        Process current = process;
        if (current != null && current.isAlive() && writer != null) {
            return;
        }
        synchronized (lifecycleMonitor) {
            current = process;
            if (current != null && current.isAlive() && writer != null) {
                return;
            }
            startProcess();
        }
    }

    private void startProcess() {
        stopProcess();
        try {
            prepareRuntime();
            ProcessBuilder processBuilder = new ProcessBuilder(launchCommand())
                    .directory(runtimeRoot.toFile());
            configureEnvironment(processBuilder.environment());
            log.info("Starting Codex App Server model={} effort={} proxyConfigured={} runtime={}",
                    model, effort, !proxyUrl.isBlank(), runtimeRoot);
            Process started = processBuilder.start();
            process = started;
            writer = new BufferedWriter(new OutputStreamWriter(started.getOutputStream(), StandardCharsets.UTF_8));
            attachedThreads.clear();
            synchronized (recentErrors) {
                recentErrors.clear();
            }
            Thread.ofVirtual().name("codex-app-server-output").start(() -> readOutput(started));
            Thread.ofVirtual().name("codex-app-server-errors").start(() -> readErrors(started));

            ObjectNode params = objectMapper.createObjectNode();
            ObjectNode clientInfo = params.putObject("clientInfo");
            clientInfo.put("name", "novel-agent");
            clientInfo.put("title", "Novel Agent");
            clientInfo.put("version", "0.1.0");
            params.putObject("capabilities").put("experimentalApi", true);
            requestOnStartedProcess("initialize", params);

            ObjectNode initialized = objectMapper.createObjectNode();
            initialized.put("method", "initialized");
            initialized.set("params", objectMapper.createObjectNode());
            writeMessage(initialized);
            log.info("Codex App Server initialized pid={} model={}", started.pid(), model);
        }
        catch (IOException exception) {
            log.error("Codex App Server startup failed category={} exceptionType={}",
                    failureCategory(exception.getMessage()), exception.getClass().getSimpleName());
            stopProcess();
            throw new CodexAppServerException("无法启动 Codex App Server：" + exception.getMessage(), exception);
        }
        catch (RuntimeException exception) {
            log.error("Codex App Server initialization failed category={} exceptionType={}",
                    failureCategory(exception.getMessage()), exception.getClass().getSimpleName());
            stopProcess();
            throw exception;
        }
    }

    List<String> launchCommand() {
        return List.of(resolveCommand(command, System.getenv("LOCALAPPDATA")),
                "app-server", "--listen", "stdio://");
    }

    static String resolveCommand(String configuredCommand, String localAppData) {
        if (!"codex.exe".equalsIgnoreCase(configuredCommand)
                || localAppData == null || localAppData.isBlank()) {
            return configuredCommand;
        }
        Path bin = Path.of(localAppData, "OpenAI", "Codex", "bin");
        if (!Files.isDirectory(bin)) {
            return configuredCommand;
        }
        try (var directories = Files.list(bin)) {
            return directories.map(directory -> directory.resolve("codex.exe"))
                    .filter(Files::isRegularFile)
                    .max(Comparator.comparingLong(file -> file.toFile().lastModified()))
                    .map(Path::toString)
                    .orElse(configuredCommand);
        }
        catch (IOException | SecurityException exception) {
            return configuredCommand;
        }
    }

    Map<String, String> runtimeEnvironment() {
        return Map.of(
                "CODEX_HOME", codexHome.toString(),
                "CODEX_SQLITE_HOME", stateRoot.toString());
    }

    void configureEnvironment(Map<String, String> environment) {
        HOST_CODEX_VARIABLES.forEach(environment::remove);
        PROXY_VARIABLES.forEach(name -> {
            if (isSandboxBlackholeProxy(environment.get(name))) {
                environment.remove(name);
            }
        });
        if (!proxyUrl.isBlank()) {
            environment.put("ALL_PROXY", proxyUrl);
            environment.put("HTTP_PROXY", proxyUrl);
            environment.put("HTTPS_PROXY", proxyUrl);
        }
        environment.putAll(runtimeEnvironment());
    }

    private static boolean isSandboxBlackholeProxy(String value) {
        if (value == null) {
            return false;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return normalized.equals("http://127.0.0.1:9")
                || normalized.equals("https://127.0.0.1:9")
                || normalized.equals("socks5://127.0.0.1:9");
    }

    void prepareRuntime() throws IOException {
        Files.createDirectories(runtimeRoot);
        Files.createDirectories(codexHome);
        Files.createDirectories(stateRoot);
        Path target = codexHome.resolve("auth.json");
        if (Files.notExists(target)) {
            if (Files.notExists(authSource)) {
                throw new IOException("Codex 登录凭据不存在：" + authSource);
            }
            Files.copy(authSource, target, StandardCopyOption.COPY_ATTRIBUTES);
        }
    }

    private void readOutput(Process owner) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(owner.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    handleMessage(objectMapper.readTree(line));
                }
            }
            failConnection(owner, "Codex App Server 已停止输出");
        }
        catch (Exception exception) {
            failConnection(owner, "Codex App Server 通信中断：" + exception.getMessage());
        }
    }

    private void readErrors(Process owner) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(owner.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (recentErrors) {
                    if (recentErrors.size() == STDERR_LINE_LIMIT) {
                        recentErrors.removeFirst();
                    }
                    recentErrors.addLast(line);
                }
            }
        }
        catch (IOException ignored) {
            // The output reader owns connection failure handling.
        }
    }

    void handleMessage(JsonNode message) {
        JsonNode id = message.get("id");
        if (id != null && id.isIntegralNumber() && (message.has("result") || message.has("error"))) {
            CompletableFuture<JsonNode> future = pendingRequests.get(id.asLong());
            if (future != null) {
                future.complete(message);
            }
            return;
        }

        String method = message.path("method").asText("");
        if (id != null && !method.isBlank()) {
            rejectServerRequest(id, method);
            return;
        }
        switch (method) {
            case "thread/tokenUsage/updated" -> handleTokenUsage(message.path("params"));
            case "item/agentMessage/delta", "item/completed", "turn/completed" ->
                    handleTurnEvent(method, message.path("params"));
            default -> {
                // Reasoning and tool content are deliberately not exposed as model responses.
            }
        }
    }

    private void handleTurnEvent(String method, JsonNode params) {
        String threadId = params.path("threadId").asText();
        String turnId = method.equals("turn/completed") ? params.at("/turn/id").asText()
                : params.path("turnId").asText();
        if (method.equals("turn/completed")) abandonedTurns.remove(threadId, turnId);
        ActiveTurn active = activeTurns.get(threadId);
        if (active == null || turnId.isBlank()) return;
        synchronized (active) {
            if (active.turnId == null) {
                if (active.earlyEvents.size() < 1000) active.earlyEvents.add(new PendingEvent(method, params));
                return;
            }
            if (!active.turnId.equals(turnId)) return;
            switch (method) {
                case "item/agentMessage/delta" -> active.append(params.path("itemId").asText(), params.path("delta").asText());
                case "item/completed" -> handleItemCompleted(params);
                case "turn/completed" -> handleTurnCompleted(params);
                default -> { }
            }
        }
    }

    private void handleTokenUsage(JsonNode params) {
        ActiveTurn activeTurn = activeTurns.get(params.path("threadId").asText());
        String turnId = params.path("turnId").asText("");
        if (activeTurn == null || turnId.isBlank()) return;
        activeTurn.recordUsage(turnId, Usage.from(params.at("/tokenUsage/last"), true));
    }

    private void handleItemCompleted(JsonNode params) {
        ActiveTurn activeTurn = activeTurns.get(params.path("threadId").asText());
        JsonNode item = params.path("item");
        if (activeTurn != null && "agentMessage".equals(item.path("type").asText())) {
            activeTurn.output = item.path("text").asText("");
            activeTurn.publish(activeTurn.output);
        }
    }

    private void handleTurnCompleted(JsonNode params) {
        ActiveTurn activeTurn = activeTurns.get(params.path("threadId").asText());
        if (activeTurn == null) {
            return;
        }
        JsonNode turn = params.path("turn");
        String status = turn.path("status").asText();
        if (!"completed".equals(status)) {
            String partialOutput = findLastAgentMessage(turn.path("items"));
            if (!partialOutput.isBlank()) activeTurn.publish(partialOutput);
            String message = turn.at("/error/message").asText("状态为 " + status);
            log.warn("Codex turn notification failed threadId={} turnId={} status={} category={}",
                    params.path("threadId").asText(""), turn.path("id").asText(""), status,
                    failureCategory(message));
            activeTurn.completion.completeExceptionally(
                    new CodexAppServerException("Codex 生成未完成：" + message));
            return;
        }
        String finalOutput = findLastAgentMessage(turn.path("items"));
        if (!finalOutput.isBlank()) activeTurn.publish(finalOutput);
        activeTurn.completion.complete(finalOutput.isBlank() ? activeTurn.output : finalOutput);
    }

    private String findLastAgentMessage(JsonNode items) {
        String output = "";
        if (items.isArray()) {
            for (JsonNode item : items) {
                if ("agentMessage".equals(item.path("type").asText())) {
                    output = item.path("text").asText("");
                }
            }
        }
        return output;
    }

    private void rejectServerRequest(JsonNode id, String method) {
        ObjectNode response = objectMapper.createObjectNode();
        response.set("id", id);
        ObjectNode error = response.putObject("error");
        error.put("code", -32601);
        error.put("message", "Novel Agent does not allow server request: " + method);
        writeMessage(response);
    }

    private void writeMessage(JsonNode message) {
        BufferedWriter current = writer;
        if (current == null) {
            throw new CodexAppServerException("Codex App Server 尚未启动");
        }
        synchronized (writeMonitor) {
            try {
                current.write(objectMapper.writeValueAsString(message));
                current.newLine();
                current.flush();
            }
            catch (IOException exception) {
                throw new CodexAppServerException("无法向 Codex App Server 发送请求", exception);
            }
        }
    }

    private <T> T await(CompletableFuture<T> future, String action) {
        return await(future, action, timeout);
    }

    // Obtain the turn ID before honoring an interrupt so a just-started remote turn can be stopped.
    JsonNode awaitTurnStart(CompletableFuture<JsonNode> future) {
        long deadline = System.nanoTime() + timeout.toNanos();
        boolean interrupted = false;
        try {
            while (true) {
                try {
                    return future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                } catch (InterruptedException exception) {
                    interrupted = true;
                } catch (TimeoutException exception) {
                    throw new CodexAppServerException("调用 Codex turn/start 超时", exception);
                } catch (ExecutionException exception) {
                    throw new CodexAppServerException("调用 Codex turn/start 失败", exception.getCause());
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    private <T> T await(CompletableFuture<T> future, String action, Duration limit) {
        try {
            return future.get(limit.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (TimeoutException exception) {
            throw new CodexAppServerException(action + "超时（等待上限 " + limit.toSeconds()
                    + " 秒），可降低推理强度或切换模型后手动重试；生成上限配置 CODEX_TURN_TIMEOUT_SECONDS，协议上限 CODEX_CLI_TIMEOUT_SECONDS", exception);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CodexAppServerException(action + "被中断", exception);
        }
        catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof CodexAppServerException codexException) {
                throw codexException;
            }
            throw new CodexAppServerException(action + "失败", cause);
        }
    }

    private Path prepareWorkspace(UUID projectId) {
        Path workspace = runtimeRoot.resolve(projectId.toString()).normalize();
        if (!workspace.startsWith(runtimeRoot)) {
            throw new CodexAppServerException("Codex 工作目录不合法");
        }
        try {
            return Files.createDirectories(workspace).toAbsolutePath();
        }
        catch (IOException exception) {
            throw new CodexAppServerException("无法创建 Codex 工作目录", exception);
        }
    }

    private ObjectNode readOnlySandbox() {
        ObjectNode sandbox = objectMapper.createObjectNode();
        sandbox.put("type", "readOnly");
        sandbox.put("networkAccess", false);
        return sandbox;
    }

    private static String requiredText(JsonNode node, String pointer, String message) {
        String value = node.at(pointer).asText("");
        if (value.isBlank()) {
            throw new CodexAppServerException(message);
        }
        return value;
    }

    private void failConnection(Process owner, String message) {
        if (process != owner) {
            return;
        }
        String details;
        synchronized (recentErrors) {
            details = String.join(" | ", recentErrors);
        }
        log.error("Codex App Server connection lost pid={} category={} stderrLines={}",
                owner.pid(), failureCategory(details.isBlank() ? message : details),
                recentErrorCount());
        CodexAppServerException failure = new CodexAppServerException(
                details.isBlank() ? message : message + "：" + abbreviate(details));
        pendingRequests.values().forEach(future -> future.completeExceptionally(failure));
        activeTurns.values().forEach(turn -> turn.completion.completeExceptionally(failure));
        writer = null;
        process = null;
        attachedThreads.clear();
        abandonedTurns.clear();
    }

    private int recentErrorCount() {
        synchronized (recentErrors) {
            return recentErrors.size();
        }
    }

    static String failureCategory(String message) {
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (normalized.contains("unauthorized") || normalized.contains("authentication")
                || normalized.contains("401") || normalized.contains("403")) return "AUTHENTICATION";
        if (normalized.contains("workspace routing discovery failed")) return "WORKSPACE_ROUTING";
        if (normalized.contains("no rollout found") || normalized.contains("thread not found")) return "MISSING_THREAD";
        if (normalized.contains("proxy") || normalized.contains("connect") || normalized.contains("network"))
            return "NETWORK";
        if (normalized.contains("timeout") || normalized.contains("超时")) return "TIMEOUT";
        return "OTHER";
    }

    private static String abbreviate(String value) {
        String normalized = value.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "...";
    }

    @PreDestroy
    public void close() {
        synchronized (lifecycleMonitor) {
            stopProcess();
        }
    }

    private void stopProcess() {
        BufferedWriter currentWriter = writer;
        writer = null;
        if (currentWriter != null) {
            try {
                currentWriter.close();
            }
            catch (IOException ignored) {
                // Process shutdown below is authoritative.
            }
        }
        Process currentProcess = process;
        process = null;
        if (currentProcess != null && currentProcess.isAlive()) {
            currentProcess.destroy();
            try {
                if (!currentProcess.waitFor(2, TimeUnit.SECONDS)) {
                    currentProcess.destroyForcibly();
                }
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                currentProcess.destroyForcibly();
            }
        }
        attachedThreads.clear();
    }

    public record TurnResult(String turnId, String output, Usage usage) implements ModelResult {
        public TurnResult(String turnId, String output) { this(turnId, output, null); }
    }

    private static final class TurnFailure extends CodexAppServerException implements UsageCarrier {
        private final Usage usage;

        private TurnFailure(RuntimeException cause, Usage usage) {
            super(cause.getMessage(), cause);
            this.usage = usage;
        }

        @Override public Usage usage() { return usage; }
    }

    private record PendingEvent(String method, JsonNode params) { }

    private static final class ActiveTurn {
        private final CompletableFuture<String> completion = new CompletableFuture<>();
        private final Consumer<String> progress;
        private final List<PendingEvent> earlyEvents = new ArrayList<>();
        private String itemId = "";
        private final StringBuilder partial = new StringBuilder();
        private volatile String turnId;
        private volatile String output = "";
        private final Map<String, Usage> usages = new ConcurrentHashMap<>();

        private ActiveTurn(Consumer<String> progress) { this.progress = progress; }

        private void append(String id, String delta) {
            if (!itemId.equals(id)) { itemId = id; partial.setLength(0); }
            int available = com.novelagent.agent.application.AgentRunOutputBuffer.MAX_CHARACTERS + 1 - partial.length();
            if (available > 0) partial.append(delta, 0, Math.min(delta.length(), available));
            publish(partial.toString());
        }

        private void publish(String text) {
            try { progress.accept(text); }
            catch (RuntimeException exception) {
                log.warn("Codex progress observer failed exceptionType={}", exception.getClass().getSimpleName());
            }
        }

        private void recordUsage(String usageTurnId, Usage usage) {
            if (usage != null && (turnId == null || turnId.equals(usageTurnId))) {
                usages.put(usageTurnId, usage);
            }
        }

        private Usage currentUsage() { return turnId == null ? null : usages.get(turnId); }
    }
}
