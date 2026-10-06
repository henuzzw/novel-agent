package com.novelagent.platform.web;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class HttpRequestLogFilter extends OncePerRequestFilter {
    @Override protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(request.getContextPath() + "/api/");
    }
    @Override protected boolean shouldNotFilterAsyncDispatch() { return false; }

    @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        var state = HttpRequestLog.from(request);
        boolean first = state == null;
        if (first) {
            state = new HttpRequestLog(request);
            request.setAttribute(HttpRequestLog.ATTRIBUTE, state);
            response.setHeader("X-Request-Id", state.requestId);
        }
        String oldRequest = MDC.get("requestId"), oldProject = MDC.get("projectId");
        MDC.put("requestId", state.requestId); MDC.put("projectId", state.projectId);
        if (first) HttpRequestLog.LOG.info("HTTP_START requestId={} projectId={} method={} path={}",
                state.requestId, state.projectId, state.method, state.path);
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException | Error error) {
            state.errorType = error.getClass().getSimpleName(); state.failureStatus = 500;
            throw error;
        } finally {
            try {
                if (request.isAsyncStarted()) {
                    if (first) {
                        state.output = "[ASYNC_STREAM metadata only]";
                        try {
                            request.getAsyncContext().addListener(listener(state, response));
                            HttpRequestLog.LOG.info("HTTP_ASYNC_OPEN requestId={} projectId={} status={}",
                                    state.requestId, state.projectId, response.getStatus());
                        } catch (IllegalStateException completedBeforeListener) {
                            state.finish(response);
                        }
                    }
                } else state.finish(response);
            } finally {
                restore("requestId", oldRequest); restore("projectId", oldProject);
            }
        }
    }

    private AsyncListener listener(HttpRequestLog state, HttpServletResponse response) {
        return new AsyncListener() {
            @Override public void onComplete(AsyncEvent event) { state.finish(response); }
            @Override public void onTimeout(AsyncEvent event) { state.timedOut = true; }
            @Override public void onError(AsyncEvent event) {
                state.errorType = event.getThrowable() == null ? "AsyncError" : event.getThrowable().getClass().getSimpleName();
                state.failureStatus = 500;
            }
            @Override public void onStartAsync(AsyncEvent event) { event.getAsyncContext().addListener(this); }
        };
    }
    private static void restore(String key, String value) {
        if (value == null) MDC.remove(key); else MDC.put(key, value);
    }
}
