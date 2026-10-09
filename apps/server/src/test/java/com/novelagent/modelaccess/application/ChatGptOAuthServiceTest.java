package com.novelagent.modelaccess.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.modelaccess.infrastructure.ChatGptHttp;
import com.novelagent.modelaccess.infrastructure.PrivateOAuthStore;
import com.novelagent.project.application.CurrentActorProvider;
import java.net.URI;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;

class ChatGptOAuthServiceTest {
    @TempDir Path directory;
    private final ObjectMapper json = new ObjectMapper();
    private final ChatGptHttp http = mock(ChatGptHttp.class);
    private final CurrentActorProvider actors = mock(CurrentActorProvider.class);
    private final JwtDecoder decoder = mock(JwtDecoder.class);
    private final UUID user = UUID.randomUUID();
    private PrivateOAuthStore store;
    private ChatGptOAuthService service;
    @BeforeEach void setup() {
        store = new PrivateOAuthStore(json, directory.resolve("private").toString());
        when(actors.currentUserId()).thenReturn(user);
        service = new ChatGptOAuthService(store, http, actors, decoder);
    }
    @AfterEach void cleanup() { service.close(); }
    private Jwt identity(String nonce, String subject) {
        return Jwt.withTokenValue("identity").header("alg", "RS256").subject(subject).audience(List.of("oaiapp_test"))
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(3600)).claim("nonce", nonce).build();
    }
    private com.fasterxml.jackson.databind.JsonNode tokens(String refresh) {
        return json.createObjectNode().put("id_token", "identity").put("token_type", "Bearer").put("access_token", "access-test")
                .put("refresh_token", refresh).put("expires_in", 3600).put("scope", "resource.invoke chatgpt.tokens.use.direct");
    }
    private Map<String, String> params(ChatGptOAuthService.Login login) {
        return ChatGptOAuthService.callbackQuery(login.authorizationUrl(), ChatGptHttp.AUTH.toString());
    }
    private String callback(Map<String, String> params, String state) {
        return params.get("redirect_uri") + "?" + ChatGptHttp.encode(Map.of("code", "test-code", "state", state, "client_id", "oaiapp_test"));
    }
    @Test void pkceNonceAndStateBindCallbackAndReplayCannotExchangeAgain() {
        var login = service.begin(); var p = params(login);
        assertThat(p).containsEntry("client_id", "dynamic_agent_client").containsEntry("code_challenge_method", "S256")
                .containsEntry("resource", "https://api.openai.com/v1");
        assertThat(p.get("ext_agent_host_id")).startsWith("urn:uuid:");
        assertThatThrownBy(() -> service.complete(login.attemptId(), callback(p, "wrong"))).hasMessageContaining("state");
        verifyNoInteractions(http);
        when(http.form(eq(ChatGptHttp.TOKEN), anyMap())).thenReturn(tokens("refresh-first"));
        when(decoder.decode("identity")).thenReturn(identity(p.get("nonce"), "subject"));
        service.complete(login.attemptId(), callback(p, p.get("state")));
        assertThat(service.status().connected()).isTrue(); assertThat(service.status().canGenerate()).isTrue();
        assertThat(service.authorization().toString()).doesNotContain("access-test", "refresh-first");
        assertThatThrownBy(() -> service.complete(login.attemptId(), callback(p, p.get("state")))).hasMessageContaining("已完成");
        verify(http, times(1)).form(eq(ChatGptHttp.TOKEN), anyMap());
        var form = org.mockito.ArgumentCaptor.forClass(Map.class);
        verify(http).form(eq(ChatGptHttp.TOKEN), form.capture());
        assertThat(form.getValue().get("redirect_uri")).isEqualTo(p.get("redirect_uri"));
        assertThat(form.getValue().get("code_verifier").toString()).hasSize(43);
    }
    @Test void badIdentityNonceOrSignatureNeverInstallsCredentials() {
        var login = service.begin(); var p = params(login);
        when(http.form(eq(ChatGptHttp.TOKEN), anyMap())).thenReturn(tokens("refresh"));
        when(decoder.decode("identity")).thenReturn(identity("different", "subject"));
        assertThatThrownBy(() -> service.complete(login.attemptId(), callback(p, p.get("state")))).hasMessageContaining("登录绑定");
        assertThat(service.status().connected()).isFalse();
        var second = service.begin(); var q = params(second);
        when(decoder.decode("identity")).thenThrow(new JwtException("bad signature"));
        assertThatThrownBy(() -> service.complete(second.attemptId(), callback(q, q.get("state")))).hasMessageContaining("身份签名");
        assertThat(service.status().connected()).isFalse();
    }
    @Test void cancelledOrAmbiguousCallbackCannotExchangeCredentials() {
        var login = service.begin(); var p = params(login);
        assertThatThrownBy(() -> service.complete(login.attemptId(), callback(p, p.get("state")) + "&state=duplicate"))
                .hasMessageContaining("重复参数");
        assertThatThrownBy(() -> service.complete(login.attemptId(), "http://example.com/auth/callback?code=test"))
                .hasMessageContaining("不一致");
        service.cancel();
        assertThatThrownBy(() -> service.complete(login.attemptId(), callback(p, p.get("state")))).hasMessageContaining("已完成");
        verifyNoInteractions(http);
    }
    private void saveExpired(String scope) {
        store.locked(user, () -> { store.save(user, new PrivateOAuthStore.Credentials("oaiapp_test", "subject", null,
                null, "expired", "refresh-old", 1, scope)); return null; });
    }
    @Test void parallelConsumersRefreshOnceAndRotationPersistsUnderFileLock() throws Exception {
        saveExpired("chatgpt.tokens.use.direct");
        when(http.form(eq(ChatGptHttp.TOKEN), anyMap())).thenReturn(tokens("refresh-new"));
        when(decoder.decode("identity")).thenReturn(identity(null, "subject"));
        try (var workers = Executors.newFixedThreadPool(3)) {
            var calls = workers.invokeAll(List.<java.util.concurrent.Callable<PrivateOAuthStore.Credentials>>of(
                    service::authorization, service::authorization, service::authorization));
            for (var call : calls) assertThat(call.get().refreshToken()).isEqualTo("refresh-new");
        }
        verify(http, times(1)).form(eq(ChatGptHttp.TOKEN), anyMap());
        assertThat(store.locked(user, () -> store.read(user)).refreshToken()).isEqualTo("refresh-new");
    }
    @Test void invalidGrantClearsTokensAndMissingPermissionNeverFallsBack() {
        saveExpired("chatgpt.tokens.use.direct");
        when(http.form(eq(ChatGptHttp.TOKEN), anyMap())).thenThrow(ChatGptHttp.failure(400, "{\"error\":\"invalid_grant\"}", ""));
        assertThatThrownBy(service::authorization).hasMessageContaining("invalid_grant");
        assertThat(service.status().connected()).isFalse();
        saveExpired("openid profile");
        assertThatThrownBy(service::authorization).hasMessageContaining("尚未授权模型用量");
        verify(http, times(1)).form(eq(ChatGptHttp.TOKEN), anyMap());
    }
    @Test void logoutClearsLocalGrantEvenIfRevocationUnavailableAndRetainsRegistration() {
        saveExpired("chatgpt.tokens.use.direct");
        when(http.get(ChatGptHttp.DISCOVERY, null)).thenThrow(new ChatGptAccessException("NETWORK", "offline"));
        assertThat(service.logout().revocationConfirmed()).isFalse();
        assertThat(service.status().connected()).isFalse();
        var registration = store.locked(user, () -> store.read(user));
        assertThat(registration.clientId()).isEqualTo("oaiapp_test"); assertThat(registration.refreshToken()).isNull();
    }
    @Test void realJwtDecoderVerifiesSignatureIssuerAndExpiryBeforeInstallingIdentity() throws Exception {
        service.close();
        var generator = java.security.KeyPairGenerator.getInstance("RSA"); generator.initialize(2048);
        var keys = generator.generateKeyPair();
        var verified = org.springframework.security.oauth2.jwt.NimbusJwtDecoder.withPublicKey((java.security.interfaces.RSAPublicKey) keys.getPublic()).build();
        verified.setJwtValidator(org.springframework.security.oauth2.jwt.JwtValidators.createDefaultWithIssuer("https://auth.openai.com"));
        service = new ChatGptOAuthService(store, http, actors, verified);
        var login = service.begin(); var p = params(login);
        var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer("https://auth.openai.com").subject("subject")
                .audience("oaiapp_test").issueTime(java.util.Date.from(Instant.now())).expirationTime(java.util.Date.from(Instant.now().plusSeconds(3600)))
                .claim("nonce", p.get("nonce")).build();
        var signed = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.RS256), claims);
        signed.sign(new com.nimbusds.jose.crypto.RSASSASigner(keys.getPrivate()));
        var returned = tokens("refresh-signed"); ((com.fasterxml.jackson.databind.node.ObjectNode) returned).put("id_token", signed.serialize());
        when(http.form(eq(ChatGptHttp.TOKEN), anyMap())).thenReturn(returned);
        service.complete(login.attemptId(), callback(p, p.get("state")));
        assertThat(service.status().connected()).isTrue();
        var next = service.begin(); var q = params(next);
        var wrongKeys = generator.generateKeyPair();
        var forged = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader(com.nimbusds.jose.JWSAlgorithm.RS256), claims);
        forged.sign(new com.nimbusds.jose.crypto.RSASSASigner(wrongKeys.getPrivate()));
        ((com.fasterxml.jackson.databind.node.ObjectNode) returned).put("id_token", forged.serialize());
        assertThatThrownBy(() -> service.complete(next.attemptId(), callback(q, q.get("state")))).hasMessageContaining("身份签名");
        assertThat(service.status().connected()).isTrue();
        assertThat(store.hostId()).isEqualTo(new PrivateOAuthStore(json, directory.resolve("private").toString()).hostId());
    }
}
