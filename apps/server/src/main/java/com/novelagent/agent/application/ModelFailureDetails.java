package com.novelagent.agent.application;

import java.util.Locale;

public record ModelFailureDetails(String type, String category, String summary, String detail) {
    public static ModelFailureDetails from(RuntimeException exception) {
        String text = exception.getMessage() == null ? "未提供具体错误信息" : exception.getMessage();
        String normalized = text.toLowerCase(Locale.ROOT);
        String category;
        String summary;
        if (exception instanceof GenerationStoppedException) {
            category = "CANCELLED";
            summary = text;
        } else if (text.startsWith("原文解析输出校验失败")) {
            category = "OUTPUT_VALIDATION";
            summary = "模型已返回内容，但原文解析结构或证据校验未通过，请查看具体字段与响应；未保存为有效报告。";
        } else if (hasTimeoutCause(exception) || normalized.contains("timeout") || normalized.contains("超时")) {
            category = "TIMEOUT";
            summary = text.contains("连续无新进展")
                    ? "模型长时间没有新的生成进展，等待超时；未完成响应不能作为规划结果。请检查连接或调整空闲等待上限后手动重试。"
                    : "模型生成等待超时；未完成响应不能作为规划结果。可降低推理强度或调整等待上限后手动重试。";
        } else if (normalized.contains("usage limit") || normalized.contains("quota") || normalized.contains("429")) {
            category = "USAGE_LIMIT"; summary = "模型额度不足或请求受限，请检查额度后手动重试。";
        } else if (normalized.contains("401") || normalized.contains("403") || normalized.contains("authentication")) {
            category = "AUTHENTICATION"; summary = "模型认证或访问权限失败，请检查登录和模型权限。";
        } else if (normalized.contains("connect") || normalized.contains("network") || normalized.contains("proxy")) {
            category = "NETWORK"; summary = "模型连接失败，请检查网络或代理。";
        } else {
            category = "OTHER"; summary = "模型调用失败，请展开响应与错误详情查看原因。";
        }
        return new ModelFailureDetails(exception.getClass().getSimpleName(), category, summary, sanitize(text));
    }

    private static boolean hasTimeoutCause(Throwable cause) {
        for (int i = 0; cause != null && i < 8; i++, cause = cause.getCause()) {
            if (cause instanceof java.util.concurrent.TimeoutException
                    || cause instanceof java.net.http.HttpTimeoutException) return true;
        }
        return false;
    }

    static String sanitize(String text) {
        String safe = text.replaceAll("(?i)Bearer\\s+[^\\s,;\"}]+", "Bearer [REDACTED]")
                .replaceAll("(?i)(api[_-]?key|access[_-]?token|refresh[_-]?token|password|authorization)([\\s\"']*[:=][\\s\"']*)[^\\s\"',;}]+", "$1$2[REDACTED]")
                .replaceAll("\\bsk-[A-Za-z0-9_-]+", "[REDACTED]")
                .replaceAll("[A-Za-z]:[\\\\/][^\\r\\n\"<>|]+", "[LOCAL_PATH]")
                .replaceAll("(?i)(https?://)[^\\s/@]+:[^\\s/@]+@", "$1[REDACTED]@")
                .replaceAll("(https?://[^\\s?]+)\\?[^\\s]+", "$1?[REDACTED]");
        return safe.length() <= 2000 ? safe : safe.substring(0, 2000) + "…";
    }
}
