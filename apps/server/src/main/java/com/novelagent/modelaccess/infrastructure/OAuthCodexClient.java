package com.novelagent.modelaccess.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.modelaccess.application.ChatGptAccessException;
import com.novelagent.modelaccess.application.ChatGptOAuthService;
import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.project.application.GlobalModelSettingsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Dedicated OAuth runtime: token only in child environment, never CLI arguments or auth.json. */
@Component
public class OAuthCodexClient extends CodexAppServerClient {
    private final ObjectMapper json;
    private final CodexConversationStore materials;
    private final ChatGptOAuthService auth;
    private final Map<String, Scope> scopes = new ConcurrentHashMap<>();
    private String accessToken;
    private String binding;
    private final ModelContextProperties context;
    public OAuthCodexClient(ObjectMapper json, CodexConversationStore materials, ChatGptOAuthService auth,
            GlobalModelSettingsService settings, ModelContextProperties context,
            @Value("${app.ai.codex.command}") String command,
            @Value("${app.ai.codex.model}") String model,
            @Value("${app.ai.codex.effort}") String effort,
            @Value("${app.ai.codex.timeout-seconds}") long timeout,
            @Value("${app.ai.codex.turn-timeout-seconds:1200}") long idle,
            @Value("${app.ai.codex.runtime-directory}") String root,
            @Value("${app.ai.codex.proxy-url:}") String proxy) {
        super(json, command, model, effort, timeout, root + "-oauth", "unused-oauth-auth-source", proxy, settings, idle);
        this.json = json; this.materials = materials; this.auth = auth;
        this.context = context;
    }
    public synchronized String authorize() {
        var credentials = auth.authorization();
        String currentBinding = ChatGptResponsesClient.accountBinding(credentials);
        if (!credentials.accessToken().equals(accessToken) || !currentBinding.equals(binding)) {
            if (hasActiveTurns() || !scopes.isEmpty())
                throw new ChatGptAccessException("AUTHENTICATION", "登录凭据已更新，需等待当前 Codex 任务结束后再使用新凭据");
            close();
            accessToken = credentials.accessToken(); binding = currentBinding;
        }
        return binding;
    }
    public synchronized void openScope(String thread, CodexConversationStore.Lease lease, Set<String> ids, String expectedBinding) {
        if (!expectedBinding.equals(binding)) throw new ChatGptAccessException("AUTHENTICATION", "账号发生变化，未发送旧会话资料");
        scopes.put(thread, new Scope(lease, Set.copyOf(ids)));
    }
    public void closeScope(String thread) { scopes.remove(thread); }
    @Override protected boolean externalAuthorization() { return true; }
    @Override protected List<String> launchCommand() {
        var args = new ArrayList<>(super.launchCommand());
        var capacity = context.require(ModelProvider.LOCAL_CODEX);
        args.add("-c"); args.add("model_context_window=" + capacity.getContextWindowTokens());
        args.add("-c"); args.add("model_auto_compact_token_limit=" + Math.max(1,
                capacity.getContextWindowTokens() - capacity.getSafetyMarginTokens() - 24000));
        for (String setting : List.of("model_provider=\"openai_chatgpt_plan\"",
                "model_providers.openai_chatgpt_plan.name=\"ChatGPT plan\"",
                "model_providers.openai_chatgpt_plan.base_url=\"https://api.openai.com/v1\"",
                "model_providers.openai_chatgpt_plan.env_key=\"ACCESS_TOKEN\"",
                "model_providers.openai_chatgpt_plan.wire_api=\"responses\"",
                "model_providers.openai_chatgpt_plan.requires_openai_auth=false",
                "model_providers.openai_chatgpt_plan.supports_websockets=false",
                "features.shell_tool=false", "features.unified_exec=false", "features.multi_agent=false",
                "features.apps=false", "features.hooks=false", "features.memories=false", "web_search=\"disabled\"")) {
            args.add("-c"); args.add(setting);
        }
        return args;
    }
    @Override protected void configureEnvironment(Map<String, String> environment) {
        super.configureEnvironment(environment);
        environment.remove("OPENAI_API_KEY");
        if (accessToken == null) throw new ChatGptAccessException("AUTHENTICATION", "请先登录 ChatGPT");
        environment.put("ACCESS_TOKEN", accessToken);
    }
    @Override protected ArrayNode dynamicTools() {
        return CodexMaterialReadTool.definitions(json);
    }
    @Override protected JsonNode handleDynamicTool(JsonNode params) {
        Scope scope = scopes.get(params.path("threadId").asText());
        return CodexMaterialReadTool.respond(json, params, scope == null ? Set.of() : scope.ids(),
                id -> materials.readMaterial(scope.lease(), id));
    }
    private record Scope(CodexConversationStore.Lease lease, Set<String> ids) { }
}
