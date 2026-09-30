package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexAppServerClientTest {

    @Test
    void classifiesProviderFailuresWithoutExposingRawErrorText() {
        assertThat(CodexAppServerClient.failureCategory("workspace routing discovery failed"))
                .isEqualTo("WORKSPACE_ROUTING");
        assertThat(CodexAppServerClient.failureCategory("no rollout found for thread id"))
                .isEqualTo("MISSING_THREAD");
        assertThat(CodexAppServerClient.failureCategory("proxy connection failed"))
                .isEqualTo("NETWORK");
        assertThat(CodexAppServerClient.failureCategory("request timeout"))
                .isEqualTo("TIMEOUT");
        assertThat(CodexAppServerClient.failureCategory("Bearer sensitive-value"))
                .isEqualTo("OTHER");
    }

    @Test
    void reportsProcessLaunchFailure(@TempDir Path temp) throws Exception {
        Path sourceAuth = temp.resolve("auth.json");
        Files.writeString(sourceAuth, "{}");
        CodexAppServerClient client = new CodexAppServerClient(
                new ObjectMapper(), temp.resolve("missing-codex.exe").toString(), "gpt-6-sol", 1,
                temp.resolve("runtime").toString(), sourceAuth.toString(), "");

        assertThatThrownBy(() -> client.startThread(UUID.randomUUID(), "test"))
                .isInstanceOf(CodexAppServerException.class)
                .hasMessageContaining("无法启动 Codex App Server：")
                .hasMessageContaining("missing-codex.exe");
    }

    @Test
    void isolatesRuntimeAndAuthenticationFromDesktopCodexHome(@TempDir Path temp) throws Exception {
        Path sourceAuth = temp.resolve("desktop-auth.json");
        Files.writeString(sourceAuth, "{\"auth_mode\":\"chatgpt\"}");
        Path runtime = temp.resolve("runtime");
        CodexAppServerClient client = new CodexAppServerClient(
                new ObjectMapper(), "codex.exe", "gpt-6-sol", 600, runtime.toString(), sourceAuth.toString(), "");

        assertThat(client.launchCommand())
                .endsWith("app-server", "--listen", "stdio://");
        assertThat(client.launchCommand().getFirst()).endsWith("codex.exe");
        assertThat(client.runtimeEnvironment())
                .containsEntry("CODEX_HOME", runtime.resolve("home").toAbsolutePath().toString())
                .containsEntry("CODEX_SQLITE_HOME", runtime.resolve("home/state").toAbsolutePath().toString());

        client.prepareRuntime();

        assertThat(runtime.resolve("home/auth.json")).hasSameTextualContentAs(sourceAuth);
        assertThat(client.runtimeEnvironment().values()).noneMatch(value -> value.contains(".codex"));
    }

    @Test
    void findsDesktopCodexWhenItIsMissingFromPath(@TempDir Path temp) throws Exception {
        Path bin = temp.resolve("OpenAI/Codex/bin/release/codex.exe");
        Files.createDirectories(bin.getParent());
        Files.writeString(bin, "");

        assertThat(CodexAppServerClient.resolveCommand("codex.exe", temp.toString()))
                .isEqualTo(bin.toString());
        assertThat(CodexAppServerClient.resolveCommand("custom-codex.exe", temp.toString()))
                .isEqualTo("custom-codex.exe");
    }

    @Test
    void removesHostSandboxVariablesButPreservesRealProxy(@TempDir Path temp) throws Exception {
        Path sourceAuth = temp.resolve("auth.json");
        Files.writeString(sourceAuth, "{}");
        CodexAppServerClient client = new CodexAppServerClient(
                new ObjectMapper(), "codex.exe", "gpt-6-sol", 600, temp.resolve("runtime").toString(), sourceAuth.toString(), "");
        Map<String, String> environment = new HashMap<>();
        environment.put("CODEX_SANDBOX_NETWORK_DISABLED", "1");
        environment.put("CODEX_THREAD_ID", "host-thread");
        environment.put("HTTP_PROXY", "http://127.0.0.1:9");
        environment.put("HTTPS_PROXY", "http://127.0.0.1:7890");

        client.configureEnvironment(environment);

        assertThat(environment)
                .doesNotContainKeys("CODEX_SANDBOX_NETWORK_DISABLED", "CODEX_THREAD_ID", "HTTP_PROXY")
                .containsEntry("HTTPS_PROXY", "http://127.0.0.1:7890")
                .containsKeys("CODEX_HOME", "CODEX_SQLITE_HOME");
    }

    @Test
    void appliesDedicatedCodexProxyWithoutChangingOtherServices(@TempDir Path temp) throws Exception {
        Path sourceAuth = temp.resolve("auth.json");
        Files.writeString(sourceAuth, "{}");
        CodexAppServerClient client = new CodexAppServerClient(
                new ObjectMapper(), "codex.exe", "gpt-6-sol", 600, temp.resolve("runtime").toString(), sourceAuth.toString(),
                "http://127.0.0.1:7897");
        Map<String, String> environment = new HashMap<>();

        client.configureEnvironment(environment);

        assertThat(environment)
                .containsEntry("ALL_PROXY", "http://127.0.0.1:7897")
                .containsEntry("HTTP_PROXY", "http://127.0.0.1:7897")
                .containsEntry("HTTPS_PROXY", "http://127.0.0.1:7897");
    }
}
