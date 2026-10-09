package com.novelagent.modelaccess.api;

import com.novelagent.modelaccess.application.ChatGptOAuthService;
import com.novelagent.modelaccess.application.ChatGptTransportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/** Only sanitized connection metadata reaches the browser. The browser never receives bearer/refresh tokens. */
@RestController
@RequestMapping("/api/v1/settings/model/chatgpt")
public class ChatGptConnectionController {
    private final ChatGptOAuthService auth;
    private final ChatGptTransportService transport;
    public ChatGptConnectionController(ChatGptOAuthService auth, ChatGptTransportService transport) {
        this.auth = auth; this.transport = transport;
    }
    @GetMapping public Connection get() { return new Connection(transport.get(), auth.status()); }
    @PutMapping("/transport") public ChatGptTransportService.Choice update(@RequestBody ChatGptTransportService.Choice choice) {
        return transport.update(choice);
    }
    @PostMapping("/login") public ChatGptOAuthService.Login login() { transport.requireIdle(); return auth.begin(); }
    @PostMapping("/callback") public Connection callback(@Valid @RequestBody Callback callback) {
        auth.complete(callback.attemptId(), callback.callbackUrl()); return get();
    }
    @PostMapping("/login/cancel") public Connection cancel() { auth.cancel(); return get(); }
    @PostMapping("/logout") public ChatGptOAuthService.Logout logout() { transport.requireIdle(); return auth.logout(); }
    public record Callback(@NotNull UUID attemptId, @NotBlank @Size(max = 12000) String callbackUrl) { }
    public record Connection(ChatGptTransportService.Choice choice, ChatGptOAuthService.Status auth) { }
}
