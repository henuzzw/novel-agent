package com.novelagent.modelaccess.api;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.platform.web.HttpLogSanitizer;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class ChatGptConnectionGuardTest {
    @Test void connectionManagementRequiresConfiguredKeyAndDoesNotLogCredentials() throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/settings/model/chatgpt/login");
        request.setServletPath("/api/v1/settings/model/chatgpt/login");
        var denied = new MockHttpServletResponse();
        new ChatGptConnectionGuard("test-management").doFilter(request, denied, (a, b) -> { throw new AssertionError("unguarded"); });
        assertThat(denied.getStatus()).isEqualTo(403);
        var disabled = new MockHttpServletResponse();
        new ChatGptConnectionGuard("").doFilter(request, disabled, (a, b) -> { throw new AssertionError("unguarded"); });
        assertThat(disabled.getStatus()).isEqualTo(503);
        request.addHeader("X-ChatGPT-Admin-Key", "test-management");
        var allowed = new MockHttpServletResponse();
        new ChatGptConnectionGuard("test-management").doFilter(request, allowed, (a, b) -> b.getWriter().write("allowed"));
        assertThat(allowed.getContentAsString()).isEqualTo("allowed");
        var safe = new HttpLogSanitizer(new ObjectMapper()).summarize(Map.of("X-ChatGPT-Admin-Key", "test-management",
                "authorizationUrl", "https://auth.openai.com?state=private-state&id_token_hint=private-id", "callbackUrl", "http://127.0.0.1?code=private-code"));
        assertThat(safe).doesNotContain("test-management", "private-state", "private-id", "private-code");
    }
}
