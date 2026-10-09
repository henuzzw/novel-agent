package com.novelagent.modelaccess.application;

import com.novelagent.modelaccess.infrastructure.ChatGptHttp;
import com.novelagent.modelaccess.infrastructure.PrivateOAuthStore;
import com.novelagent.modelaccess.infrastructure.PrivateOAuthStore.Credentials;
import com.novelagent.project.application.CurrentActorProvider;
import com.sun.net.httpserver.HttpServer;
import jakarta.annotation.PreDestroy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

/** Owns app-specific SIWC grants, not the desktop Codex login or novel facts. */
@Service
public class ChatGptOAuthService {
    private static final String ISSUER = "https://auth.openai.com";
    private static final String RESOURCE = "https://api.openai.com/v1";
    private static final String SCOPES = "openid profile email offline_access resource.invoke chatgpt.tokens.use.direct";
    private final PrivateOAuthStore store;
    private final ChatGptHttp http;
    private final CurrentActorProvider actors;
    private final JwtDecoder decoder;
    private final Map<UUID, Attempt> attempts = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timers = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread thread = new Thread(r, "chatgpt-login-expiry"); thread.setDaemon(true); return thread;
    });

    @org.springframework.beans.factory.annotation.Autowired
    public ChatGptOAuthService(PrivateOAuthStore store, ChatGptHttp http, CurrentActorProvider actors) {
        this(store, http, actors, decoder(http));
    }
    ChatGptOAuthService(PrivateOAuthStore store, ChatGptHttp http, CurrentActorProvider actors, JwtDecoder decoder) {
        this.store = store; this.http = http; this.actors = actors; this.decoder = decoder;
    }
    private static JwtDecoder decoder(ChatGptHttp http) {
        var factory = new JdkClientHttpRequestFactory(http.client());
        factory.setReadTimeout(Duration.ofSeconds(45));
        var decoder = NimbusJwtDecoder.withJwkSetUri(ISSUER + "/.well-known/jwks.json")
                .restOperations(new RestTemplate(factory)).build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtIssuerValidator(ISSUER),
                new JwtTimestampValidator(Duration.ofSeconds(5))));
        return decoder;
    }

    public Status status() {
        UUID user = actors.currentUserId();
        Credentials credentials = store.locked(user, () -> store.read(user));
        Attempt attempt = attempts.get(user);
        return new Status(credentials != null && credentials.accessToken() != null,
                credentials != null && credentials.canGenerate(), credentials == null ? null : credentials.email(),
                credentials == null || credentials.expiresAt() == 0 ? null : Instant.ofEpochSecond(credentials.expiresAt()),
                attempt == null ? "IDLE" : attempt.status, attempt == null ? null : attempt.error,
                attempt == null ? null : attempt.id, attempt == null ? null : attempt.expiresAt);
    }

    public synchronized Login begin() {
        UUID user = actors.currentUserId();
        cancel(user);
        Credentials saved = store.locked(user, () -> store.read(user));
        String host = store.hostId();
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            String callback = "http://127.0.0.1:" + server.getAddress().getPort() + "/auth/callback";
            var attempt = new Attempt(saved, callback, server);
            server.createContext("/auth/callback", exchange -> {
                String message;
                int status;
                try {
                    if (!"GET".equals(exchange.getRequestMethod()) || !"/auth/callback".equals(exchange.getRequestURI().getPath()))
                        throw new IllegalArgumentException("回调路径或方法不正确");
                    complete(user, attempt.id, callback + "?" + exchange.getRequestURI().getRawQuery());
                    status = 200; message = "ChatGPT connected. Return to Novel Agent.";
                } catch (RuntimeException error) {
                    status = 400; message = "Sign-in failed. Check Novel Agent settings and start a new sign-in.";
                }
                byte[] bytes = message.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.getResponseHeaders().set("Cache-Control", "no-store");
                exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
                exchange.sendResponseHeaders(status, bytes.length);
                try (var body = exchange.getResponseBody()) { body.write(bytes); }
                if (!"PENDING".equals(attempt.status)) timers.schedule(() -> server.stop(0), 1, java.util.concurrent.TimeUnit.SECONDS);
            });
            attempts.put(user, attempt);
            server.start();
            timers.schedule(() -> {
                synchronized (attempt) {
                    if ("PENDING".equals(attempt.status) || "CONNECTING".equals(attempt.status)) {
                        attempt.status = "EXPIRED"; attempt.error = "登录已过期，请重新发起";
                    }
                    server.stop(0);
                }
            }, 10, java.util.concurrent.TimeUnit.MINUTES);
            var parameters = new LinkedHashMap<String, String>();
            parameters.put("client_id", saved == null ? "dynamic_agent_client" : saved.clientId());
            if (saved == null) parameters.put("agent_name_hint", "Novel Agent");
            else if (saved.idToken() != null) parameters.put("id_token_hint", saved.idToken());
            parameters.put("ext_agent_host_id", host); parameters.put("response_type", "code");
            parameters.put("redirect_uri", callback); parameters.put("scope", SCOPES); parameters.put("resource", RESOURCE);
            parameters.put("state", attempt.state); parameters.put("nonce", attempt.nonce);
            parameters.put("code_challenge_method", "S256"); parameters.put("code_challenge", challenge(attempt.verifier));
            return new Login(attempt.id, ChatGptHttp.AUTH + "?" + ChatGptHttp.encode(parameters), attempt.expiresAt);
        } catch (java.io.IOException error) {
            throw new ChatGptAccessException("AUTHENTICATION", "无法启动本机登录回调，请检查服务端网络权限");
        }
    }

    public void complete(UUID attemptId, String callbackUrl) { complete(actors.currentUserId(), attemptId, callbackUrl); }
    private void complete(UUID user, UUID id, String callbackUrl) {
        Attempt attempt = attempts.get(user);
        if (attempt == null || !attempt.id.equals(id)) throw new IllegalArgumentException("登录尝试不存在，请重新发起");
        Map<String, String> query = callbackQuery(callbackUrl, attempt.callback);
        synchronized (attempt) {
            if (!"PENDING".equals(attempt.status) || Instant.now().isAfter(attempt.expiresAt))
                throw new IllegalArgumentException("登录尝试已完成或过期");
            if (!MessageDigest.isEqual(attempt.state.getBytes(StandardCharsets.UTF_8),
                    query.getOrDefault("state", "").getBytes(StandardCharsets.UTF_8)))
                throw new IllegalArgumentException("登录 state 不匹配");
            attempt.status = "CONNECTING";
        }
        try {
            if (query.containsKey("error")) throw new ChatGptAccessException("AUTHENTICATION", "ChatGPT 授权未完成或已取消，请重新发起");
            String client = query.getOrDefault("client_id", attempt.previous == null ? "" : attempt.previous.clientId());
            if (!client.startsWith("oaiapp_") || attempt.previous != null && !client.equals(attempt.previous.clientId()))
                throw new ChatGptAccessException("AUTHENTICATION", "登录返回的应用标识不正确，未替换现有账号");
            String code = query.get("code");
            if (code == null || code.isBlank()) throw new IllegalArgumentException("登录缺少授权码");
            var tokens = http.form(ChatGptHttp.TOKEN, Map.of("grant_type", "authorization_code", "client_id", client,
                    "code", code, "code_verifier", attempt.verifier, "redirect_uri", attempt.callback, "resource", RESOURCE));
            Jwt identity = identity(tokens.path("id_token").asText(), client, attempt.nonce);
            if (attempt.previous != null && !attempt.previous.subject().equals(identity.getSubject()))
                throw new ChatGptAccessException("AUTHENTICATION", "重新登录的账号与应用绑定账号不同，未覆盖现有授权；请使用原账号登录");
            Credentials credentials = credentials(tokens, client, identity.getSubject(), identity.getClaimAsString("email"),
                    tokens.path("id_token").asText(), null);
            // Cancellation or a second login cannot install an obsolete callback's credentials.
            synchronized (attempt) {
                if (!"CONNECTING".equals(attempt.status) || attempts.get(user) != attempt)
                    throw new IllegalArgumentException("登录尝试已取消");
                store.locked(user, () -> { store.save(user, credentials); return null; });
                attempt.status = "SUCCEEDED";
            }
        } catch (RuntimeException error) {
            synchronized (attempt) {
                if ("CONNECTING".equals(attempt.status)) {
                    attempt.status = "FAILED";
                    attempt.error = error instanceof ChatGptAccessException ? error.getMessage() : "登录校验失败，请重新授权";
                }
            }
            throw error;
        }
    }

    /** Every call re-reads the canonical profile under a cross-process lock before refresh. */
    public Credentials authorization() {
        UUID user = actors.currentUserId();
        return store.locked(user, () -> {
            Credentials current = store.read(user);
            if (current == null || current.accessToken() == null)
                throw new ChatGptAccessException("AUTHENTICATION", "ChatGPT authentication：请先在全局设置中登录");
            if (!current.canGenerate()) throw new ChatGptAccessException("AUTHENTICATION", "ChatGPT authentication：尚未授权模型用量，请重新登录并允许使用 ChatGPT 额度");
            if (current.expiresAt() > Instant.now().getEpochSecond() + 30) return current;
            if (current.refreshToken() == null) throw new ChatGptAccessException("AUTHENTICATION", "ChatGPT authentication：授权已过期，请重新登录");
            try {
                var response = http.form(ChatGptHttp.TOKEN, Map.of("grant_type", "refresh_token", "client_id", current.clientId(),
                        "refresh_token", current.refreshToken(), "resource", RESOURCE));
                String idToken = response.path("id_token").asText(current.idToken());
                if (response.hasNonNull("id_token") && !current.subject().equals(identity(idToken, current.clientId(), null).getSubject()))
                    throw new ChatGptAccessException("AUTHENTICATION", "刷新返回的账号不一致，请重新登录");
                var renewed = credentials(response, current.clientId(), current.subject(), current.email(), idToken, current.scope());
                store.save(user, renewed);
                return renewed;
            } catch (ChatGptAccessException error) {
                if (error.getMessage().contains("invalid_grant")) store.save(user, current.signedOut());
                throw error;
            }
        });
    }

    public synchronized void cancel() { cancel(actors.currentUserId()); }
    private void cancel(UUID user) {
        Attempt attempt = attempts.get(user);
        if (attempt != null) synchronized (attempt) { attempt.status = "CANCELLED"; attempt.server.stop(0); }
    }
    public synchronized Logout logout() {
        UUID user = actors.currentUserId(); cancel(user);
        boolean revoked = store.locked(user, () -> {
            Credentials current = store.read(user);
            if (current == null) return true;
            boolean success = current.refreshToken() == null;
            try {
                if (current.refreshToken() != null) {
                    String endpoint = http.get(ChatGptHttp.DISCOVERY, null).path("revocation_endpoint").asText();
                    URI uri = URI.create(endpoint);
                    if (!"https".equals(uri.getScheme()) || !"auth.openai.com".equals(uri.getHost()) || uri.getUserInfo() != null
                            || uri.getPort() != -1 && uri.getPort() != 443 || uri.getFragment() != null)
                        throw new IllegalArgumentException("Invalid revocation endpoint");
                    http.form(uri, Map.of("token", current.refreshToken(), "token_type_hint", "refresh_token", "client_id", current.clientId()));
                    success = true;
                }
            } catch (RuntimeException ignored) { }
            store.save(user, current.signedOut());
            return success;
        });
        return new Logout(revoked, revoked ? "已断开 ChatGPT" : "已清除本地授权，远端撤销未确认，请到 ChatGPT 设置中断开应用");
    }

    private Jwt identity(String token, String client, String nonce) {
        try {
            Jwt jwt = decoder.decode(token);
            if (jwt.getSubject() == null || jwt.getSubject().isBlank() || jwt.getExpiresAt() == null || jwt.getIssuedAt() == null
                    || jwt.getIssuedAt().isAfter(Instant.now().plusSeconds(5))
                    || !jwt.getAudience().contains(client) || nonce != null && !nonce.equals(jwt.getClaimAsString("nonce")))
                throw new IllegalArgumentException("Invalid identity claims");
            return jwt;
        } catch (RuntimeException error) { throw new ChatGptAccessException("AUTHENTICATION", "ChatGPT 身份签名、有效期或登录绑定校验失败，未保存凭据"); }
    }
    private static Credentials credentials(com.fasterxml.jackson.databind.JsonNode tokens, String client, String subject,
            String email, String idToken, String priorScope) {
        long lifetime = tokens.path("expires_in").asLong(0);
        if (!"Bearer".equalsIgnoreCase(tokens.path("token_type").asText()) || tokens.path("access_token").asText().isBlank()
                || tokens.path("refresh_token").asText().isBlank() || lifetime <= 0 || lifetime > 86400)
            throw new ChatGptAccessException("AUTHENTICATION", "ChatGPT 返回的授权信息不完整，请重新登录");
        return new Credentials(client, subject, email, idToken, tokens.path("access_token").asText(),
                tokens.path("refresh_token").asText(), Instant.now().getEpochSecond() + lifetime,
                tokens.path("scope").asText(priorScope == null ? "" : priorScope));
    }
    static Map<String, String> callbackQuery(String value, String expected) {
        if (value == null || value.length() > 12000) throw new IllegalArgumentException("回调地址不合法");
        URI uri = URI.create(value);
        if (uri.getRawFragment() != null || uri.getUserInfo() != null || !expected.equals(value.split("\\?", 2)[0]) || uri.getRawQuery() == null)
            throw new IllegalArgumentException("回调地址与本次登录不一致");
        Map<String, String> query = new LinkedHashMap<>();
        for (String part : uri.getRawQuery().split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String text = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (query.putIfAbsent(key, text) != null) throw new IllegalArgumentException("回调包含重复参数");
        }
        return query;
    }
    private static String random() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
    private static String challenge(String verifier) {
        try { return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII))); }
        catch (java.security.NoSuchAlgorithmException error) { throw new IllegalStateException(error); }
    }
    @PreDestroy void close() { attempts.values().forEach(a -> a.server.stop(0)); timers.shutdownNow(); }
    private static final class Attempt {
        final UUID id = UUID.randomUUID();
        final String state = random(), nonce = random(), verifier = random(), callback;
        final Credentials previous;
        final HttpServer server;
        final Instant expiresAt = Instant.now().plusSeconds(600);
        volatile String status = "PENDING", error;
        Attempt(Credentials previous, String callback, HttpServer server) { this.previous = previous; this.callback = callback; this.server = server; }
    }
    public record Status(boolean connected, boolean canGenerate, String email, Instant expiresAt,
            String loginStatus, String error, UUID attemptId, Instant loginExpiresAt) { }
    public record Login(UUID attemptId, String authorizationUrl, Instant expiresAt) { }
    public record Logout(boolean revocationConfirmed, String message) { }
}
