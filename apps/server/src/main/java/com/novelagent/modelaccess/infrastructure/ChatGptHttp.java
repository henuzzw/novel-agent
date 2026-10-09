package com.novelagent.modelaccess.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.GenerationStoppedException;
import com.novelagent.modelaccess.application.ChatGptAccessException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Shared OAuth/catalog/Responses transport. Redirects are disabled to protect bearer credentials. */
@Component
public class ChatGptHttp {
    public static final URI AUTH = URI.create("https://auth.openai.com/api/accounts/authorize");
    public static final URI TOKEN = URI.create("https://auth.openai.com/api/accounts/oauth/token");
    public static final URI DISCOVERY = URI.create("https://auth.openai.com/.well-known/openid-configuration");
    public static final URI MODELS = URI.create("https://api.openai.com/v1/models");
    public static final URI RESPONSES = URI.create("https://api.openai.com/v1/responses");
    private final HttpClient client;
    private final ObjectMapper json;

    public ChatGptHttp(ObjectMapper json, @Value("${app.ai.codex.proxy-url:}") String proxy) {
        this.json = json;
        var builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NEVER);
        if (proxy != null && !proxy.isBlank()) {
            URI uri = URI.create(proxy);
            if (!"http".equals(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null)
                throw new IllegalArgumentException("ChatGPT 直连代理需要无账号密码的 http://host:port 地址");
            builder.proxy(ProxySelector.of(new InetSocketAddress(uri.getHost(), uri.getPort() < 0 ? 80 : uri.getPort())));
        }
        this.client = builder.build();
    }

    public HttpClient client() { return client; }
    public JsonNode get(URI uri, String bearer) {
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45)).header("Accept", "application/json");
        if (bearer != null) request.header("Authorization", "Bearer " + bearer);
        return exchange(request.GET().build());
    }
    public JsonNode form(URI uri, Map<String, String> form) {
        return exchange(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(45))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(encode(form))).build());
    }
    public static String encode(Map<String, String> values) {
        return values.entrySet().stream().map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }

    private JsonNode exchange(HttpRequest request) {
        try {
            var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw failure(response.statusCode(), response.body(), response.headers().firstValue("x-request-id").orElse(""));
            return response.body().isBlank() ? json.createObjectNode() : json.readTree(response.body());
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new GenerationStoppedException();
        } catch (java.io.IOException error) {
            throw new ChatGptAccessException("NETWORK", "ChatGPT network 请求失败，请检查代理或网络；未自动重试");
        }
    }

    public static ChatGptAccessException failure(int status, String body, String requestId) {
        String code = "unknown_error";
        try {
            var node = new ObjectMapper().readTree(body);
            code = node.path("error").isTextual() ? node.path("error").asText()
                    : node.path("error").path("code").asText(node.path("code").asText("unknown_error"));
        } catch (Exception ignored) { }
        // Error codes and request IDs are identifiers, not arbitrary provider text or echoed inputs.
        code = safeIdentifier(code);
        String category = status == 401 || status == 403 || "invalid_grant".equals(code) ? "AUTHENTICATION"
                : status == 429 || code.contains("usage_limit") || code.contains("usage_unavailable") ? "USAGE_LIMIT" : "PROVIDER";
        String advice = "AUTHENTICATION".equals(category) ? "请检查授权、账号和模型权限，必要时重新登录"
                : "USAGE_LIMIT".equals(category) ? "额度不足或请求受限，请检查 ChatGPT 用量" : "模型请求未完成，请检查接口或请求配置";
        return new ChatGptAccessException(category, "ChatGPT HTTP " + status + "，错误码 " + code
                + (requestId.isBlank() ? "" : "，请求编号 " + safeIdentifier(requestId)) + "；" + advice);
    }
    private static String safeIdentifier(String value) {
        return value != null && value.matches("[a-zA-Z0-9_.:-]{1,128}") ? value : "unavailable";
    }
}
