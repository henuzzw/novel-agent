package com.novelagent.modelaccess.api;

import com.novelagent.modelaccess.application.ChatGptOAuthService;
import com.novelagent.modelaccess.application.ChatGptTransportService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * ChatGPT 连接管理接口。仅返回脱敏连接元信息；接入切换、登录和退出由相应服务校验，不向浏览器暴露访问或刷新令牌。
 */
@RestController
@RequestMapping("/api/v1/settings/model/chatgpt")
public class ChatGptConnectionController {
    private final ChatGptOAuthService auth;
    private final ChatGptTransportService transport;

    public ChatGptConnectionController(
            ChatGptOAuthService auth, ChatGptTransportService transport) {
        this.auth = auth;
        this.transport = transport;
    }

    /**
     * 返回当前用户选择的接入方式与脱敏授权状态。
     */
    @GetMapping
    public Connection get() {
        return new Connection(transport.get(), auth.status());
    }

    /**
     * 按行版本更新接入方式；存在运行任务或有效会话租约时拒绝切换。
     *
     * @param choice 目标接入方式及预期配置行版本
     */
    @PutMapping("/transport")
    public ChatGptTransportService.Choice update(
            @RequestBody ChatGptTransportService.Choice choice) {
        return transport.update(choice);
    }

    /**
     * 确认当前无运行模型任务后启动独立 OAuth 登录，返回浏览器授权地址及有效期。
     */
    @PostMapping("/login")
    public ChatGptOAuthService.Login login() {
        transport.requireIdle();
        return auth.begin();
    }

    /**
     * 完成指定登录尝试的回调校验并返回脱敏连接状态，不返回凭据。
     *
     * @param callback 登录尝试 ID 及浏览器回调地址
     */
    @PostMapping("/callback")
    public Connection callback(@Valid @RequestBody Callback callback) {
        auth.complete(callback.attemptId(), callback.callbackUrl());
        return get();
    }

    /**
     * 取消当前用户待完成的登录尝试，保留既有有效授权。
     */
    @PostMapping("/login/cancel")
    public Connection cancel() {
        auth.cancel();
        return get();
    }

    /**
     * 确认模型任务空闲后撤销并清理本应用授权；不处理其他应用的登录。
     */
    @PostMapping("/logout")
    public ChatGptOAuthService.Logout logout() {
        transport.requireIdle();
        return auth.logout();
    }

    public record Callback(
            @NotNull UUID attemptId, @NotBlank @Size(max = 12000) String callbackUrl) {}

    public record Connection(
            ChatGptTransportService.Choice choice, ChatGptOAuthService.Status auth) {}
}
