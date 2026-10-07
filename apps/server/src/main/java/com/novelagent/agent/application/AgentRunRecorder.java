package com.novelagent.agent.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.platform.support.Sha256;
import com.novelagent.planning.application.ModelProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AgentRunRecorder {
    private static final Logger log = LoggerFactory.getLogger(AgentRunRecorder.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private final JdbcTemplate jdbc;
    private final BigDecimal deepSeekInputPrice;
    private final BigDecimal deepSeekOutputPrice;
    private final BigDecimal codexInputPrice;
    private final BigDecimal codexOutputPrice;
    private final AgentRunOutputBuffer outputs;
    private final GenerationControlRegistry controls;
    private final ThreadLocal<Consumer<String>> progress = new ThreadLocal<>();

    @Autowired
    public AgentRunRecorder(JdbcTemplate jdbc,
            @Value("${app.ai.cost.deepseek-input-per-million:0}") BigDecimal deepSeekInputPrice,
            @Value("${app.ai.cost.deepseek-output-per-million:0}") BigDecimal deepSeekOutputPrice,
            @Value("${app.ai.cost.codex-input-per-million:0}") BigDecimal codexInputPrice,
            @Value("${app.ai.cost.codex-output-per-million:0}") BigDecimal codexOutputPrice,
            AgentRunOutputBuffer outputs, GenerationControlRegistry controls) {
        this.jdbc = jdbc;
        this.deepSeekInputPrice = deepSeekInputPrice;
        this.deepSeekOutputPrice = deepSeekOutputPrice;
        this.codexInputPrice = codexInputPrice;
        this.codexOutputPrice = codexOutputPrice;
        this.outputs = outputs;
        this.controls = controls;
    }

    public AgentRunRecorder(JdbcTemplate jdbc, BigDecimal deepSeekInputPrice, BigDecimal deepSeekOutputPrice,
            BigDecimal codexInputPrice, BigDecimal codexOutputPrice, AgentRunOutputBuffer outputs) {
        this(jdbc, deepSeekInputPrice, deepSeekOutputPrice, codexInputPrice, codexOutputPrice,
                outputs, new GenerationControlRegistry());
    }

    public AgentRunRecorder(JdbcTemplate jdbc, BigDecimal deepSeekInputPrice, BigDecimal deepSeekOutputPrice,
            BigDecimal codexInputPrice, BigDecimal codexOutputPrice) {
        this(jdbc, deepSeekInputPrice, deepSeekOutputPrice, codexInputPrice, codexOutputPrice, new AgentRunOutputBuffer());
    }

    public Consumer<String> progressSink() {
        Consumer<String> sink = progress.get();
        return sink == null ? ignored -> { } : sink;
    }

    public <T> T record(UUID projectId, String stage, ModelProvider provider,
            String systemPrompt, String userPrompt,
            Supplier<T> action) {
        return record(projectId, stage, provider, systemPrompt, userPrompt, null, action,
                result -> result instanceof ModelResult modelResult ? modelResult.usage() : null,
                result -> result instanceof ModelResult modelResult ? modelResult.output()
                        : result == null ? "" : result.toString(), ignored -> { });
    }

    public <T extends ModelResult> T record(UUID projectId, String stage, ModelProvider provider,
            String systemPrompt, String userPrompt, RequestSnapshot snapshot, Supplier<T> action) {
        return record(projectId, stage, provider, systemPrompt, userPrompt, snapshot, action,
                ModelResult::usage, ModelResult::output, ignored -> { });
    }

    public <T extends ModelResult> T record(UUID projectId, String stage, ModelProvider provider,
            String systemPrompt, String userPrompt, RequestSnapshot snapshot, Supplier<T> action,
            Consumer<T> processOutput) {
        return record(projectId, stage, provider, systemPrompt, userPrompt, snapshot, action,
                ModelResult::usage, ModelResult::output, processOutput);
    }

    private <T> T record(UUID projectId, String stage, ModelProvider provider,
            String systemPrompt, String userPrompt, RequestSnapshot snapshot, Supplier<T> action,
            Function<T, Usage> usageReader, Function<T, String> outputReader, Consumer<T> processOutput) {
        UUID id = UUID.randomUUID();
        Instant startedAt = Instant.now();
        long inputTokens = snapshot != null && snapshot.contextBudget() != null
                ? snapshot.contextBudget().estimatedInputTokens()
                : estimateTokens(systemPrompt) + estimateTokens(userPrompt);
        jdbc.update("""
                INSERT INTO agent_run(id, project_id, stage, provider, status, prompt_preview,
                    system_prompt, user_prompt, input_tokens, started_at, request_snapshot,
                    estimated_input_tokens, token_source)
                VALUES (?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, 'ESTIMATED')
                """, id, projectId, stage, provider.name(), preview(userPrompt),
                systemPrompt, userPrompt, inputTokens,
                Timestamp.from(startedAt), json(snapshot), inputTokens);
        log.info("Agent run started runId={} projectId={} stage={} provider={}",
                id, projectId, stage, provider);
        outputs.start(projectId, id);
        Consumer<String> previous = progress.get();
        progress.set(text -> outputs.replace(id, text));
        Usage receivedUsage = null;
        GenerationControlRegistry.ActiveCall control = null;
        try {
            control = controls.start(projectId, id);
            T result = action.get();
            String response = outputReader.apply(result);
            outputs.replace(id, response);
            receivedUsage = usageReader.apply(result);
            control.beginSaving();
            processOutput.accept(result);
            long outputTokens = estimateTokens(response);
            finish(id, "SUCCEEDED", provider, startedAt, inputTokens, outputTokens,
                    receivedUsage, null, outputs.get(projectId, id));
            log.info("Agent run completed runId={} projectId={} stage={} provider={} durationMs={}",
                    id, projectId, stage, provider, Duration.between(startedAt, Instant.now()).toMillis());
            return result;
        } catch (RuntimeException exception) {
            boolean stopped = control != null && control.isStopped();
            if (stopped) Thread.interrupted();
            log.warn("Agent run ended runId={} projectId={} stage={} provider={} durationMs={} status={} exceptionType={}",
                    id, projectId, stage, provider, Duration.between(startedAt, Instant.now()).toMillis(),
                    stopped ? "CANCELLED" : "FAILED", exception.getClass().getSimpleName());
            Usage usage = receivedUsage != null ? receivedUsage : exception instanceof UsageCarrier carrier ? carrier.usage() : null;
            var output = outputs.get(projectId, id);
            RuntimeException failure = stopped ? new GenerationStoppedException() : exception;
            finish(id, stopped ? "CANCELLED" : "FAILED", provider, startedAt, inputTokens, estimateTokens(output.text()), usage,
                    ModelFailureDetails.from(failure), output);
            throw failure;
        } finally {
            if (control != null) control.close();
            outputs.remove(id);
            if (previous == null) progress.remove(); else progress.set(previous);
        }
    }

    private void finish(UUID id, String status, ModelProvider provider, Instant startedAt,
            long estimatedInput, long estimatedOutput, Usage usage, ModelFailureDetails error,
            AgentRunOutputBuffer.Output output) {
        jdbc.update("""
                UPDATE agent_run SET status = ?, input_tokens = ?, output_tokens = ?, token_source = ?,
                    actual_input_tokens = ?, actual_output_tokens = ?, usage_snapshot = CAST(? AS jsonb),
                    estimated_input_tokens = ?, estimated_output_tokens = ?, estimated_cost = ?,
                    duration_ms = ?, completed_at = ?, error_message = ?,
                    response_text = ?, response_truncated = ?, error_type = ?, error_category = ?, error_detail = ?
                    WHERE id = ?
                """, status, estimatedInput, estimatedOutput, usage == null ? "ESTIMATED" : "ACTUAL",
                usage == null ? null : usage.inputTokens(), usage == null ? null : usage.outputTokens(),
                json(usage), estimatedInput, estimatedOutput, cost(provider, estimatedInput, estimatedOutput),
                Duration.between(startedAt, Instant.now()).toMillis(), Timestamp.from(Instant.now()),
                error == null ? null : error.summary(), output.text(), output.truncated(),
                error == null ? null : error.type(), error == null ? null : error.category(),
                error == null ? null : error.detail(), id);
    }

    private static String json(Object value) {
        if (value == null) return null;
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Cannot serialize model request metadata", exception);
        }
    }

    public record EffectiveSettings(ModelProvider provider, String model, String effort, Long version) { }

    public record RequestSnapshot(EffectiveSettings effectiveSettings, String systemPromptHash,
            String userPromptHash, String schemaHash, String schemaName, int requestedMaxOutputTokens,
            Integer maxOutputTokens, String sessionPolicy, ContextBudget contextBudget) {
        public static RequestSnapshot capture(EffectiveSettings settings, String systemPrompt,
                String userPrompt, JsonNode schema, String schemaName, int maxOutputTokens, String sessionPolicy) {
            return new RequestSnapshot(settings, hash(systemPrompt), hash(userPrompt), hash(schema.toString()),
                    schemaName, maxOutputTokens,
                    settings.provider() == ModelProvider.DEEPSEEK ? maxOutputTokens : null, sessionPolicy, null);
        }

        public RequestSnapshot withContextBudget(ContextBudget budget) {
            return new RequestSnapshot(effectiveSettings, systemPromptHash, userPromptHash, schemaHash,
                    schemaName, requestedMaxOutputTokens, maxOutputTokens, sessionPolicy, budget);
        }

        private static String hash(String text) {
            return Sha256.ofUtf8(text == null ? "" : text);
        }
    }

    public record ContextBudget(int contextWindowTokens, int safetyMarginTokens,
            long estimatedInputTokens, int reservedOutputTokens) { }

    public interface UsageCarrier {
        Usage usage();
    }

    public interface ModelResult extends UsageCarrier {
        String output();
    }

    public record Usage(long inputTokens, long outputTokens, Long totalTokens,
            Long cachedInputTokens, Long reasoningOutputTokens) {
        public static Usage from(JsonNode node, boolean codex) {
            String input = codex ? "inputTokens" : "input_tokens";
            String output = codex ? "outputTokens" : "output_tokens";
            if (!valid(node.path(input)) || !valid(node.path(output))) return null;
            return new Usage(node.path(input).longValue(), node.path(output).longValue(),
                    optional(node.path(codex ? "totalTokens" : "total_tokens")),
                    optional(codex ? node.path("cachedInputTokens") : node.at("/input_tokens_details/cached_tokens")),
                    optional(codex ? node.path("reasoningOutputTokens") : node.at("/output_tokens_details/reasoning_tokens")));
        }

        private static Long optional(JsonNode node) { return valid(node) ? node.longValue() : null; }

        private static boolean valid(JsonNode node) {
            return node.isIntegralNumber() && node.canConvertToLong() && node.longValue() >= 0;
        }
    }

    private long estimateTokens(String value) {
        if (value == null || value.isBlank()) return 0;
        long cjk = value.codePoints().filter(code -> code >= 0x2E80 && code <= 0x9FFF).count();
        long other = value.codePointCount(0, value.length()) - cjk;
        return cjk + Math.max(1, Math.round(other / 4.0));
    }

    private BigDecimal cost(ModelProvider provider, long inputTokens, long outputTokens) {
        BigDecimal inputPrice = provider == ModelProvider.DEEPSEEK ? deepSeekInputPrice : codexInputPrice;
        BigDecimal outputPrice = provider == ModelProvider.DEEPSEEK ? deepSeekOutputPrice : codexOutputPrice;
        return inputPrice.multiply(BigDecimal.valueOf(inputTokens))
                .add(outputPrice.multiply(BigDecimal.valueOf(outputTokens)))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
    }

    private String preview(String value) {
        if (value == null) return "";
        String normalized = value.strip();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }
}
