package com.novelagent.agent.application;

import com.novelagent.planning.application.ModelProvider;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class AgentRunRecorder {
    private static final Logger log = LoggerFactory.getLogger(AgentRunRecorder.class);
    private final JdbcTemplate jdbc;
    private final BigDecimal deepSeekInputPrice;
    private final BigDecimal deepSeekOutputPrice;
    private final BigDecimal codexInputPrice;
    private final BigDecimal codexOutputPrice;

    public AgentRunRecorder(JdbcTemplate jdbc,
            @Value("${app.ai.cost.deepseek-input-per-million:0}") BigDecimal deepSeekInputPrice,
            @Value("${app.ai.cost.deepseek-output-per-million:0}") BigDecimal deepSeekOutputPrice,
            @Value("${app.ai.cost.codex-input-per-million:0}") BigDecimal codexInputPrice,
            @Value("${app.ai.cost.codex-output-per-million:0}") BigDecimal codexOutputPrice) {
        this.jdbc = jdbc;
        this.deepSeekInputPrice = deepSeekInputPrice;
        this.deepSeekOutputPrice = deepSeekOutputPrice;
        this.codexInputPrice = codexInputPrice;
        this.codexOutputPrice = codexOutputPrice;
    }

    public <T> T record(UUID projectId, String stage, ModelProvider provider,
            String systemPrompt, String userPrompt,
            Supplier<T> action) {
        UUID id = UUID.randomUUID();
        Instant startedAt = Instant.now();
        long inputTokens = estimateTokens(systemPrompt) + estimateTokens(userPrompt);
        jdbc.update("""
                INSERT INTO agent_run(id, project_id, stage, provider, status, prompt_preview,
                    system_prompt, user_prompt, input_tokens, started_at)
                VALUES (?, ?, ?, ?, 'RUNNING', ?, ?, ?, ?, ?)
                """, id, projectId, stage, provider.name(), preview(userPrompt),
                systemPrompt, userPrompt, inputTokens,
                Timestamp.from(startedAt));
        log.info("Agent run started runId={} projectId={} stage={} provider={}",
                id, projectId, stage, provider);
        try {
            T result = action.get();
            long outputTokens = estimateTokens(result == null ? "" : result.toString());
            jdbc.update("""
                    UPDATE agent_run SET status = 'SUCCEEDED', output_tokens = ?, estimated_cost = ?,
                        duration_ms = ?, completed_at = ? WHERE id = ?
                    """, outputTokens, cost(provider, inputTokens, outputTokens),
                    Duration.between(startedAt, Instant.now()).toMillis(), Timestamp.from(Instant.now()), id);
            log.info("Agent run completed runId={} projectId={} stage={} provider={} durationMs={}",
                    id, projectId, stage, provider, Duration.between(startedAt, Instant.now()).toMillis());
            return result;
        } catch (RuntimeException exception) {
            log.warn("Agent run failed runId={} projectId={} stage={} provider={} durationMs={} exceptionType={}",
                    id, projectId, stage, provider, Duration.between(startedAt, Instant.now()).toMillis(),
                    exception.getClass().getSimpleName());
            jdbc.update("""
                    UPDATE agent_run SET status = 'FAILED', duration_ms = ?, error_message = ?,
                        completed_at = ? WHERE id = ?
                    """, Duration.between(startedAt, Instant.now()).toMillis(),
                    preview(exception.getMessage()), Timestamp.from(Instant.now()), id);
            throw exception;
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
