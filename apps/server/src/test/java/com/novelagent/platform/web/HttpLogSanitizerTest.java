package com.novelagent.platform.web;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HttpLogSanitizerTest {
    private final HttpLogSanitizer sanitizer = new HttpLogSanitizer(new ObjectMapper());
    @Test void hidesSceneDraftsButKeepsTheirStatus() {
        String log = sanitizer.summarize(Map.of("sceneOutline", "私有场景底稿", "sceneOutlineNeedsUpdate", true));
        assertThat(log).contains("TEXT chars=", "sceneOutlineNeedsUpdate", "true").doesNotContain("私有场景底稿");
    }
    @Test void hidesEvenShortSnowflakeCreativeText() {
        String log = sanitizer.summarize(Map.of("core", "私有核心", "characters", "私有人物",
                "world", "私有世界", "plot", "私有情节", "developmentNotes", "私有底稿",
                "status", "RUNNING", "activeStage", "WORLD"));
        assertThat(log).contains("RUNNING", "WORLD", "TEXT chars=")
                .doesNotContain("私有核心", "私有人物", "私有世界", "私有情节", "私有底稿");
    }

    @Test void hidesPromptEditorFieldsWithoutDroppingVersionMetadata() {
        String log = sanitizer.summarize(Map.of("guidance", "私有规则", "defaultSystemPrompt", "默认全文",
                "protectedRules", "边界全文", "systemPrompt", "私有角色", "sessionSystemPrompt", "真实系统角色",
                "defaultSessionSystemPrompt", "默认系统角色", "key", "MANUSCRIPT", "version", 3));
        assertThat(log).contains("MANUSCRIPT", "version", "TEXT chars=")
                .doesNotContain("私有规则", "默认全文", "边界全文", "私有角色", "真实系统角色", "默认系统角色");
    }
    @Test void keepsUsefulParametersAndMasksNestedSecretsAndManuscripts() {
        String log = sanitizer.summarize(Map.of("provider", "LOCAL_CODEX", "analysisVersion", 2,
                "body", "private manuscript", "content", Map.of("chapterId", "chapter", "text", "private source"),
                "api_key", "private key", "nested", List.of(Map.of("password", "private password")),
                "headers", Map.of("Authorization", "Bearer private")));
        assertThat(log).contains("LOCAL_CODEX", "analysisVersion", "chapterId", "REDACTED", "TEXT chars=")
                .doesNotContain("private manuscript", "private source", "private key", "private password", "Bearer private");
    }
    @Test void masksQueryArraysAndCredentialsEmbeddedInMessages() {
        String log = sanitizer.summarize(Map.of("access_token", new String[] {"credential"},
                "note", "Bearer value sk-testkey password=pass api_key=abc"));
        assertThat(log).doesNotContain("credential", "Bearer value", "sk-testkey", "pass ", "=abc");
    }
    @Test void limitsTextArraysDepthAndOverallSize() {
        String log = sanitizer.summarize(Map.of("notes", java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> "public".repeat(80)).toList(), "longText", "private".repeat(1000)));
        assertThat(log.length()).isLessThan(4050);
        assertThat(log).contains("TRUNCATED").doesNotContain("private");
        assertThat(sanitizer.summarize("private raw JSON")).contains("TEXT").doesNotContain("private");
        assertThat(sanitizer.summarize(new byte[100])).isEqualTo("[BINARY bytes=100]");
    }
    @Test void doesNotMutateInputAndPreventsNewlineInjection() {
        var mapper = new ObjectMapper();
        var original = mapper.createObjectNode().put("apiKey", "secret").put("name", "a\nb\rc");
        assertThat(sanitizer.summarize(original)).contains("a b c").doesNotContain("\n", "\r", "secret");
        assertThat(original.path("apiKey").asText()).isEqualTo("secret");
    }
}
