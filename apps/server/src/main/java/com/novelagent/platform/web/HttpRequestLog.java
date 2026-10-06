package com.novelagent.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class HttpRequestLog {
    static final String ATTRIBUTE = HttpRequestLog.class.getName();
    static final Logger LOG = LoggerFactory.getLogger(HttpRequestLog.class);
    private static final Pattern PROJECT = Pattern.compile("/api/v1/projects/([0-9a-fA-F-]{36})(?:/|$)");
    final String requestId = UUID.randomUUID().toString();
    final long started = System.nanoTime();
    final String method;
    final String path;
    final AtomicBoolean completed = new AtomicBoolean();
    volatile String projectId = "-";
    volatile String input = "[NOT_READ]";
    volatile String output = "[NO_BODY]";
    volatile String errorType = "-";
    volatile boolean timedOut;
    volatile int failureStatus;

    HttpRequestLog(HttpServletRequest request) {
        method = request.getMethod();
        String uri = request.getRequestURI().replaceAll("[\\r\\n\\p{Cntrl}]", "");
        path = uri.length() <= 1024 ? uri : uri.substring(0, 1024) + "[TRUNCATED]";
        var match = PROJECT.matcher(path);
        if (match.find()) try { projectId = UUID.fromString(match.group(1)).toString(); }
        catch (IllegalArgumentException ignored) { }
    }
    static HttpRequestLog from(HttpServletRequest request) {
        return (HttpRequestLog) request.getAttribute(ATTRIBUTE);
    }
    void finish(HttpServletResponse response) {
        if (!completed.compareAndSet(false, true)) return;
        LOG.info("HTTP_END requestId={} projectId={} method={} path={} status={} durationMs={} input={} output={} errorType={} timedOut={}",
                requestId, projectId, method, path, Math.max(response.getStatus(), failureStatus),
                (System.nanoTime() - started) / 1_000_000, input, output, errorType, timedOut);
    }
}
