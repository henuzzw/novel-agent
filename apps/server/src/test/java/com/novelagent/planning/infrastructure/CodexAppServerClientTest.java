package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.agent.application.AgentRunRecorder.EffectiveSettings;
import com.novelagent.planning.application.ModelProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodexAppServerClientTest {

    @Test void cancellationInterruptsTheExactRemoteTurnAndBlocksPrematureReuse(@TempDir Path temp) throws Exception {
        var mapper = new ObjectMapper();
        var client = org.mockito.Mockito.spy(new CodexAppServerClient(mapper, "codex.exe", "gpt-6-sol", "high", 10,
                temp.resolve("runtime").toString(), temp.resolve("auth.json").toString(), ""));
        var started = new java.util.concurrent.CountDownLatch(1);
        org.mockito.Mockito.doAnswer(call -> {
            started.countDown();
            return mapper.readTree("{\"turn\":{\"id\":\"turn\"}}");
        }).when(client).request(org.mockito.ArgumentMatchers.eq("turn/start"), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doNothing().when(client).interruptTimedOutTurn("thread", "turn");
        var failure = new java.util.concurrent.CompletableFuture<Throwable>();
        var worker = new Thread(() -> {
            try { client.runStructuredTurn("thread", UUID.randomUUID(), "input", mapper.createObjectNode()); }
            catch (Throwable error) { failure.complete(error); }
            finally { Thread.interrupted(); }
        });
        worker.start();
        try {
            assertThat(started.await(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            worker.interrupt();
            assertThat(failure.get(3, java.util.concurrent.TimeUnit.SECONDS)).hasCauseInstanceOf(InterruptedException.class);
            org.mockito.Mockito.verify(client).interruptTimedOutTurn("thread", "turn");
            assertThatThrownBy(() -> client.runStructuredTurn("thread", UUID.randomUUID(), "input", mapper.createObjectNode()))
                    .hasMessageContaining("不能复用");
            client.handleMessage(mapper.readTree("""
                    {"method":"turn/completed","params":{"threadId":"thread","turn":{"id":"turn","status":"interrupted"}}}
                    """));
        } finally { worker.interrupt(); worker.join(3000); }
    }

    @Test void stopDuringTurnStartStillObtainsTurnIdAndPreservesInterrupt(@TempDir Path temp) throws Exception {
        var mapper = new ObjectMapper();
        var client = new CodexAppServerClient(mapper, "codex.exe", "gpt-6-sol", "high", 2,
                temp.resolve("runtime").toString(), temp.resolve("auth.json").toString(), "");
        var response = new java.util.concurrent.CompletableFuture<JsonNode>();
        var completed = new java.util.concurrent.CompletableFuture<Boolean>();
        var worker = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                assertThat(client.awaitTurnStart(response).at("/turn/id").asText()).isEqualTo("turn");
                completed.complete(Thread.currentThread().isInterrupted());
            } catch (Throwable error) { completed.completeExceptionally(error); }
            finally { Thread.interrupted(); }
        });
        worker.start();
        response.complete(mapper.readTree("{\"turn\":{\"id\":\"turn\"}}"));
        try { assertThat(completed.get(3, java.util.concurrent.TimeUnit.SECONDS)).isTrue(); }
        finally { worker.interrupt(); worker.join(3000); }
    }

    @Test void generationDeadlineIsSeparateFromProtocolRequestDeadline(@TempDir Path temp) throws Exception {
        var mapper = new ObjectMapper();
        var client = org.mockito.Mockito.spy(new CodexAppServerClient(mapper, "codex.exe", "gpt-6-sol", "high", 1,
                temp.resolve("runtime").toString(), temp.resolve("auth.json").toString(), "", null, 2));
        var scheduled = java.util.concurrent.Executors.newSingleThreadScheduledExecutor();
        try {
            JsonNode finished = mapper.readTree("""
                    {"method":"turn/completed","params":{"threadId":"thread","turn":{"id":"turn","status":"completed",
                    "items":[{"type":"agentMessage","text":"{}"}]}}}
                    """);
            org.mockito.Mockito.doAnswer(call -> {
                scheduled.schedule(() -> client.handleMessage(finished), 1200, java.util.concurrent.TimeUnit.MILLISECONDS);
                return mapper.readTree("{\"turn\":{\"id\":\"turn\"}}");
            }).when(client).request(org.mockito.ArgumentMatchers.eq("turn/start"), org.mockito.ArgumentMatchers.any());
            assertThat(client.runStructuredTurn("thread", UUID.randomUUID(), "input", mapper.createObjectNode()).output()).isEqualTo("{}");
        } finally { scheduled.shutdownNow(); }
    }

    @Test void streamsOnlyMatchingAgentMessagesAndReplacesWithFinalResponse(@TempDir Path temp) throws Exception {
        var mapper = new ObjectMapper();
        var client = org.mockito.Mockito.spy(new CodexAppServerClient(mapper, "codex.exe", "gpt-6-sol", "high", 2,
                temp.resolve("runtime").toString(), temp.resolve("auth.json").toString(), ""));
        var streamed = new java.util.ArrayList<String>();
        org.mockito.Mockito.doAnswer(call -> {
            for (String event : java.util.List.of(
                    "{\"method\":\"item/agentMessage/delta\",\"params\":{\"threadId\":\"thread\",\"turnId\":\"old\",\"itemId\":\"item\",\"delta\":\"OLD\"}}",
                    "{\"method\":\"item/reasoning/textDelta\",\"params\":{\"threadId\":\"thread\",\"turnId\":\"turn\",\"delta\":\"PRIVATE\"}}",
                    "{\"method\":\"item/agentMessage/delta\",\"params\":{\"threadId\":\"thread\",\"turnId\":\"turn\",\"itemId\":\"item\",\"delta\":\"{\"}}",
                    "{\"method\":\"item/agentMessage/delta\",\"params\":{\"threadId\":\"thread\",\"turnId\":\"turn\",\"itemId\":\"item\",\"delta\":\"}\"}}",
                    "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread\",\"turn\":{\"id\":\"old\",\"status\":\"failed\"}}}",
                    "{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"thread\",\"turn\":{\"id\":\"turn\",\"status\":\"completed\",\"items\":[{\"type\":\"agentMessage\",\"text\":\"{\\\"ok\\\":true}\"}]}}}")) {
                client.handleMessage(mapper.readTree(event));
            }
            return mapper.readTree("{\"turn\":{\"id\":\"turn\"}}");
        }).when(client).request(org.mockito.ArgumentMatchers.eq("turn/start"), org.mockito.ArgumentMatchers.any());
        var result = client.runStructuredTurn("thread", UUID.randomUUID(), "input", mapper.createObjectNode(),
                client.effectiveSettings(), streamed::add);
        assertThat(result.output()).isEqualTo("{\"ok\":true}");
        assertThat(streamed).containsExactly("{", "{}", "{\"ok\":true}");
    }

    @Test void timeoutInterruptsWithoutRetryAndBlocksReuseUntilOriginalTurnStops(@TempDir Path temp) throws Exception {
        var mapper = new ObjectMapper();
        var client = org.mockito.Mockito.spy(new CodexAppServerClient(mapper, "codex.exe", "gpt-6-sol", "xhigh", 1,
                temp.resolve("runtime").toString(), temp.resolve("auth.json").toString(), ""));
        org.mockito.Mockito.doReturn(mapper.readTree("{\"turn\":{\"id\":\"turn\"}}"))
                .when(client).request(org.mockito.ArgumentMatchers.eq("turn/start"), org.mockito.ArgumentMatchers.any());
        org.mockito.Mockito.doNothing().when(client).interruptTimedOutTurn("thread", "turn");
        UUID project = UUID.randomUUID();
        assertThatThrownBy(() -> client.runStructuredTurn("thread", project, "input", mapper.createObjectNode()))
                .hasMessageContaining("等待上限 1 秒").hasMessageContaining("CODEX_CLI_TIMEOUT_SECONDS");
        org.mockito.Mockito.verify(client).interruptTimedOutTurn("thread", "turn");
        assertThatThrownBy(() -> client.runStructuredTurn("thread", project, "input", mapper.createObjectNode()))
                .hasMessageContaining("不能复用");
        org.mockito.Mockito.verify(client, org.mockito.Mockito.times(1))
                .request(org.mockito.ArgumentMatchers.eq("turn/start"), org.mockito.ArgumentMatchers.any());
        client.handleMessage(mapper.readTree("""
                {"method":"turn/completed","params":{"threadId":"thread","turn":{"id":"turn","status":"interrupted"}}}
                """));
        org.mockito.Mockito.doAnswer(call -> {
            client.handleMessage(mapper.readTree("""
                    {"method":"turn/completed","params":{"threadId":"thread","turn":{"id":"new","status":"completed",
                    "items":[{"type":"agentMessage","text":"new output"}]}}}
                    """));
            return mapper.readTree("{\"turn\":{\"id\":\"new\"}}");
        }).when(client).request(org.mockito.ArgumentMatchers.eq("turn/start"), org.mockito.ArgumentMatchers.any());
        assertThat(client.runStructuredTurn("thread", project, "input", mapper.createObjectNode()).output()).isEqualTo("new output");
    }

    @Test
    void globalChoiceOverridesLegacyEnvironmentAndIsSharedAcrossProjects(@TempDir Path temp) {
        var settings = org.mockito.Mockito.mock(com.novelagent.project.application.GlobalModelSettingsService.class);
        org.mockito.Mockito.when(settings.get()).thenReturn(new com.novelagent.project.domain.GlobalModelSettings(
                com.novelagent.planning.application.ModelProvider.DEEPSEEK, "gpt-6-luna", "low", "deepseek-flash", 1));
        var mapper = new ObjectMapper();
        var client = new CodexAppServerClient(mapper, "codex.exe", "gpt-6-sol", "high", 600,
                temp.resolve("runtime").toString(), temp.resolve("auth.json").toString(), "", settings);
        for (int i = 0; i < 2; i++) {
            var params = client.structuredTurnParams("thread-" + i, UUID.randomUUID(), "input", mapper.createObjectNode());
            assertThat(params.path("model").asText()).isEqualTo("gpt-6-luna");
            assertThat(params.path("effort").asText()).isEqualTo("low");
        }
        assertThat(client.effectiveSettings().provider()).isEqualTo(com.novelagent.planning.application.ModelProvider.LOCAL_CODEX);
    }

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
                new ObjectMapper(), temp.resolve("missing-codex.exe").toString(), "gpt-6-sol", "xhigh", 1,
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
                new ObjectMapper(), "codex.exe", "gpt-6-sol", "xhigh", 600, runtime.toString(), sourceAuth.toString(), "");

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
                new ObjectMapper(), "codex.exe", "gpt-6-sol", "xhigh", 600, temp.resolve("runtime").toString(), sourceAuth.toString(), "");
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
                new ObjectMapper(), "codex.exe", "gpt-6-sol", "xhigh", 600, temp.resolve("runtime").toString(), sourceAuth.toString(),
                "http://127.0.0.1:7897");
        Map<String, String> environment = new HashMap<>();

        client.configureEnvironment(environment);

        assertThat(environment)
                .containsEntry("ALL_PROXY", "http://127.0.0.1:7897")
                .containsEntry("HTTP_PROXY", "http://127.0.0.1:7897")
                .containsEntry("HTTPS_PROXY", "http://127.0.0.1:7897");
    }

    @Test
    void sendsConfiguredExtremeReasoningEffortOnStructuredTurns(@TempDir Path temp) {
        ObjectMapper mapper = new ObjectMapper();
        CodexAppServerClient client = new CodexAppServerClient(
                mapper, "codex.exe", "gpt-6.1-sol", "xhigh", 600,
                temp.resolve("runtime").toString(), temp.resolve("auth.json").toString(), "");
        var params = client.structuredTurnParams("thread-1", UUID.randomUUID(), "写小说", mapper.createObjectNode());

        assertThat(params.path("model").asText()).isEqualTo("gpt-6.1-sol");
        assertThat(params.path("effort").asText()).isEqualTo("xhigh");
        assertThat(params.path("input").get(0).path("text").asText()).isEqualTo("写小说");
        assertThat(params.path("outputSchema").isObject()).isTrue();
    }

    @Test
    void frozenSettingsAreUsedForBothThreadAndTurnWithoutReadingGlobalSettings(@TempDir Path temp) {
        var settings = org.mockito.Mockito.mock(com.novelagent.project.application.GlobalModelSettingsService.class);
        var mapper = new ObjectMapper();
        var client = org.mockito.Mockito.spy(new CodexAppServerClient(mapper, "codex.exe", "gpt-6-sol", "high", 2,
                temp.resolve("runtime").toString(), temp.resolve("auth.json").toString(), "", settings));
        var frozen = new EffectiveSettings(ModelProvider.LOCAL_CODEX, "gpt-6-luna", "low", 7L);
        org.mockito.Mockito.doAnswer(call -> {
            String method = call.getArgument(0);
            JsonNode params = call.getArgument(1);
            assertThat(params.path("model").asText()).isEqualTo("gpt-6-luna");
            if (method.equals("thread/start")) {
                assertThat(params.at("/config/model_reasoning_effort").asText()).isEqualTo("low");
                return mapper.readTree("{\"thread\":{\"id\":\"thread\"}}");
            }
            assertThat(params.path("effort").asText()).isEqualTo("low");
            client.handleMessage(mapper.readTree("""
                    {"method":"turn/completed","params":{"threadId":"thread","turn":{
                    "id":"turn","status":"completed","items":[{"type":"agentMessage","text":"{}"}]}}}
                    """));
            return mapper.readTree("{\"turn\":{\"id\":\"turn\"}}");
        }).when(client).request(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());

        UUID projectId = UUID.randomUUID();
        assertThat(client.startThread(projectId, "system", frozen)).isEqualTo("thread");
        assertThat(client.runStructuredTurn("thread", projectId, "input", mapper.createObjectNode(), frozen).output())
                .isEqualTo("{}");
        org.mockito.Mockito.verifyNoInteractions(settings);
    }

    @Test
    void usageUsesMatchingTurnLastAndNeverThreadTotalIncludingEarlyNotifications(@TempDir Path temp) {
        var mapper = new ObjectMapper();
        var client = org.mockito.Mockito.spy(new CodexAppServerClient(mapper, "codex.exe", "gpt-6-sol", "high", 2,
                temp.resolve("runtime").toString(), temp.resolve("auth.json").toString(), ""));
        org.mockito.Mockito.doAnswer(call -> {
            client.handleMessage(mapper.readTree("""
                    {"method":"thread/tokenUsage/updated","params":{"threadId":"thread","turnId":"old-turn",
                    "tokenUsage":{"last":{"inputTokens":999,"outputTokens":999},"total":{"inputTokens":9999,"outputTokens":9999}}}}
                    """));
            client.handleMessage(mapper.readTree("""
                    {"method":"thread/tokenUsage/updated","params":{"threadId":"thread","turnId":"turn",
                    "tokenUsage":{"last":{"inputTokens":100,"outputTokens":10,"totalTokens":110,
                    "cachedInputTokens":30,"reasoningOutputTokens":2},"total":{"inputTokens":9999,"outputTokens":9999}}}}
                    """));
            client.handleMessage(mapper.readTree("""
                    {"method":"turn/completed","params":{"threadId":"thread","turn":{
                    "id":"turn","status":"completed","items":[{"type":"agentMessage","text":"{}"}]}}}
                    """));
            return mapper.readTree("{\"turn\":{\"id\":\"turn\"}}");
        }).when(client).request(org.mockito.ArgumentMatchers.eq("turn/start"), org.mockito.ArgumentMatchers.any());

        var result = client.runStructuredTurn("thread", UUID.randomUUID(), "input", mapper.createObjectNode());
        assertThat(result.usage().inputTokens()).isEqualTo(100);
        assertThat(result.usage().outputTokens()).isEqualTo(10);
        assertThat(result.usage().cachedInputTokens()).isEqualTo(30);
        assertThat(result.usage().reasoningOutputTokens()).isEqualTo(2);
    }

    @Test
    void unrelatedTurnUsageIsNotAttachedToCurrentResult(@TempDir Path temp) {
        var mapper = new ObjectMapper();
        var client = org.mockito.Mockito.spy(new CodexAppServerClient(mapper, "codex.exe", "gpt-6-sol", "high", 2,
                temp.resolve("runtime").toString(), temp.resolve("auth.json").toString(), ""));
        org.mockito.Mockito.doAnswer(call -> {
            client.handleMessage(mapper.readTree("""
                    {"method":"thread/tokenUsage/updated","params":{"threadId":"thread","turnId":"old-turn",
                    "tokenUsage":{"last":{"inputTokens":999,"outputTokens":999}}}}
                    """));
            client.handleMessage(mapper.readTree("""
                    {"method":"turn/completed","params":{"threadId":"thread","turn":{
                    "id":"turn","status":"completed","items":[{"type":"agentMessage","text":"{}"}]}}}
                    """));
            return mapper.readTree("{\"turn\":{\"id\":\"turn\"}}");
        }).when(client).request(org.mockito.ArgumentMatchers.eq("turn/start"), org.mockito.ArgumentMatchers.any());
        assertThat(client.runStructuredTurn("thread", UUID.randomUUID(), "input", mapper.createObjectNode()).usage()).isNull();
    }
}
