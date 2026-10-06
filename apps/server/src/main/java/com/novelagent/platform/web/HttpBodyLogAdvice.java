package com.novelagent.platform.web;

import java.lang.reflect.Type;
import org.springframework.core.MethodParameter;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.springframework.web.servlet.mvc.method.annotation.RequestBodyAdviceAdapter;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;
import com.novelagent.project.api.ProjectResponse;
import org.slf4j.MDC;

@ControllerAdvice
public class HttpBodyLogAdvice extends RequestBodyAdviceAdapter implements ResponseBodyAdvice<Object> {
    private final HttpLogSanitizer sanitizer;
    public HttpBodyLogAdvice(HttpLogSanitizer sanitizer) { this.sanitizer = sanitizer; }
    @Override public boolean supports(MethodParameter parameter, Type type, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }
    @Override public Object afterBodyRead(Object body, HttpInputMessage input, MethodParameter parameter,
            Type type, Class<? extends HttpMessageConverter<?>> converter) {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes) {
            var state = HttpRequestLog.from(attributes.getRequest());
            if (state != null) {
                state.input = sanitizer.summarize(body);
                HttpRequestLog.LOG.info("HTTP_INPUT requestId={} projectId={} body={}", state.requestId, state.projectId, state.input);
            }
        }
        return body;
    }
    @Override public boolean supports(MethodParameter parameter, Class<? extends HttpMessageConverter<?>> converter) {
        return true;
    }
    @Override public Object beforeBodyWrite(Object body, MethodParameter parameter, MediaType type,
            Class<? extends HttpMessageConverter<?>> converter, ServerHttpRequest request, ServerHttpResponse response) {
        if (request instanceof ServletServerHttpRequest servlet) {
            var state = HttpRequestLog.from(servlet.getServletRequest());
            if (state != null) {
                if (state.method.equals("POST") && state.path.equals("/api/v1/projects") && body instanceof ProjectResponse project) {
                    state.projectId = project.id().toString(); MDC.put("projectId", state.projectId);
                }
                state.output = sanitizer.summarize(body);
            }
        }
        return body;
    }
}
