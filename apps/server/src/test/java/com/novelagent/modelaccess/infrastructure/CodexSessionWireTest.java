package com.novelagent.modelaccess.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.novelagent.platform.support.Sha256;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

class CodexSessionWireTest {
    private CodexAppServerClient fixture(Path temp) {
        return new CodexAppServerClient(new ObjectMapper(), "unused", "test-model", "high", 5, temp.toString(), "unused", "") {
            @Override protected boolean externalAuthorization() { return true; }
            @Override protected List<String> launchCommand() {
                return List.of(javaBin(), "-cp",
                        System.getProperty("java.class.path"), WireServer.class.getName());
            }
        };
    }

    @Test @Timeout(15)
    void actualStdioCarriesToolDefinitionReadsAuthorizedFilesAndRejectsExpiredScopes(@TempDir Path temp) throws Exception {
        var json = new ObjectMapper();
        var client = fixture(temp);
        try {
            var project = UUID.randomUUID();
            String thread = client.startThread(project, "资料读取任务");
            String content = "完整原文\r\n".repeat(1800);
            String id = Sha256.ofUtf8(content);
            client.saveMaterialFiles(project, Map.of(id, content));
            client.openMaterialScope(thread, project, Set.of(id));
            var first = json.readTree(client.runStructuredTurn(thread, project, "read-material:" + id + ":0", null).output());
            assertThat(first.at("/toolDefinition/0/name").asText()).isEqualTo("novel_read_material");
            assertThat(first.at("/toolResult/success").asBoolean()).isTrue();
            assertThat(first.at("/toolResult/contentItems/0/text").asText()).endsWith(content.substring(0, 6000));
            assertThat(first.path("experimentalApi").asBoolean()).isTrue();
            client.resumeThread(thread, project, "本轮更新的任务");
            var next = json.readTree(client.runStructuredTurn(thread, project, "read-material:" + id + ":6000", null).output());
            assertThat(next.at("/toolResult/contentItems/0/text").asText()).endsWith(content.substring(6000));
            client.closeMaterialScope(thread);
            var denied = json.readTree(client.runStructuredTurn(thread, project, "read-material:" + id + ":0", null).output());
            assertThat(denied.at("/toolResult/success").asBoolean()).isFalse();
            assertThat(denied.toString()).doesNotContain(content.substring(0, 100));
        } finally { client.close(); }
    }

    @Test @Timeout(15)
    void actualStdioReusesThreadUpdatesInstructionsAndWaitsForCompaction(@TempDir Path temp) throws Exception {
        var json = new ObjectMapper();
        var client = new CodexAppServerClient(json, "unused", "test-model", "high", 5, temp.toString(), "unused", "") {
            @Override protected boolean externalAuthorization() { return true; }
            @Override protected List<String> launchCommand() {
                return List.of(javaBin(), "-cp",
                        System.getProperty("java.class.path"), WireServer.class.getName());
            }
        };
        try {
            var project = UUID.randomUUID();
            String thread = client.startThread(project, "初次角色");
            client.resumeThread(thread, project, "已挂载会话的新角色");
            long started = System.nanoTime(); client.compactThread(thread);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isGreaterThanOrEqualTo(100);
            var result = client.runStructuredTurn(thread, project, "本轮新增资料", null);
            var wire = json.readTree(result.output());
            assertThat(wire.path("threadId").asText()).isEqualTo(thread);
            assertThat(wire.at("/input/0/text").asText()).isEqualTo("本轮新增资料");
            assertThat(wire.has("outputSchema")).isFalse();
            assertThat(wire.path("lastDeveloperInstructions").asText()).isEqualTo("已挂载会话的新角色");
            assertThat(wire.path("compacted").asBoolean()).isTrue();
        } finally { client.close(); }
    }
    /** Local protocol fixture: no account, remote model, manuscript or network. */
    public static class WireServer {
        public static void main(String[] args) throws Exception {
            var json = new ObjectMapper(); String role = ""; boolean compacted = false;
            JsonNode definitions = json.createArrayNode();
            boolean experimental = false;
            ObjectNode pendingTool = null;
            var reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
            var out = new PrintWriter(System.out, true, StandardCharsets.UTF_8);
            String line;
            while ((line = reader.readLine()) != null) {
                var request = json.readTree(line); if (!request.has("id")) continue;
                if (request.has("result")) {
                    if (pendingTool == null) throw new IllegalStateException("Unexpected tool result");
                    pendingTool.set("toolResult", request.path("result"));
                    out.println(completion(json, pendingTool.toString()));
                    pendingTool = null;
                    continue;
                }
                var params = request.path("params"); String method = request.path("method").asText();
                var reply = json.createObjectNode(); reply.set("id", request.path("id"));
                var result = reply.putObject("result");
                if (method.equals("initialize")) experimental = params.at("/capabilities/experimentalApi").asBoolean();
                if (method.equals("thread/start")) {
                    role = params.path("developerInstructions").asText();
                    definitions = params.path("dynamicTools");
                    result.putObject("thread").put("id", "persisted-thread");
                }
                if (method.equals("thread/resume")) { role = params.path("developerInstructions").asText(); if (params.has("dynamicTools")) throw new IllegalArgumentException("Unsupported resume property"); }
                if (method.equals("turn/start")) result.putObject("turn").put("id", "generation");
                out.println(reply);
                if (method.equals("thread/compact/start")) {
                    out.println("{\"method\":\"turn/started\",\"params\":{\"threadId\":\"persisted-thread\",\"turn\":{\"id\":\"compact\"}}}");
                    Thread.sleep(120); compacted = true;
                    out.println("{\"method\":\"turn/completed\",\"params\":{\"threadId\":\"persisted-thread\",\"turn\":{\"id\":\"compact\",\"status\":\"completed\",\"items\":[]}}}");
                }
                if (method.equals("turn/start")) {
                    var captured = params.deepCopy(); ((ObjectNode) captured).put("lastDeveloperInstructions", role).put("compacted", compacted);
                    String text = params.at("/input/0/text").asText();
                    if (text.startsWith("read-material:")) {
                        var parts = text.split(":");
                        pendingTool = (ObjectNode) captured;
                        pendingTool.set("toolDefinition", definitions);
                        pendingTool.put("experimentalApi", experimental);
                        var call = json.createObjectNode().put("id", 9001).put("method", "item/tool/call");
                        call.putObject("params").put("threadId", "persisted-thread").put("turnId", "generation")
                                .put("tool", "novel_read_material").putObject("arguments")
                                .put("material_id", parts[1]).put("offset", Integer.parseInt(parts[2]));
                        out.println(call);
                    } else out.println(completion(json, captured.toString()));
                }
            }
        }
        private static JsonNode completion(ObjectMapper json, String text) {
            var event = json.createObjectNode().put("method", "turn/completed");
            event.putObject("params").put("threadId", "persisted-thread").putObject("turn").put("id", "generation").put("status", "completed")
                    .putArray("items").addObject().put("type", "agentMessage").put("text", text);
            return event;
        }
    }

    private static String javaBin() {
        String name = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }
}
