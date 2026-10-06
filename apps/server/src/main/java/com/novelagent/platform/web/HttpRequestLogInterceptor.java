package com.novelagent.platform.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

@Component
public class HttpRequestLogInterceptor implements HandlerInterceptor {
    private final HttpLogSanitizer sanitizer;
    public HttpRequestLogInterceptor(HttpLogSanitizer sanitizer) { this.sanitizer = sanitizer; }

    @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        var state = HttpRequestLog.from(request);
        if (state == null || request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC) return true;
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("pathVariables", request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE));
        parameters.put("parameters", request.getParameterMap());
        Map<String, String> headers = new LinkedHashMap<>();
        for (String name : new String[] {"Content-Type", "Accept", "If-Match", "If-None-Match", "Idempotency-Key"}) {
            if (request.getHeader(name) != null) headers.put(name, request.getHeader(name));
        }
        parameters.put("headers", headers);
        if (request instanceof MultipartHttpServletRequest multipart) {
            Map<String, Object> files = new LinkedHashMap<>();
            multipart.getMultiFileMap().forEach((name, list) -> files.put(name, list.stream().map(file -> {
                Map<String, Object> metadata = new LinkedHashMap<>();
                metadata.put("filename", file.getOriginalFilename()); metadata.put("contentType", file.getContentType());
                metadata.put("bytes", file.getSize()); return metadata;
            }).toList()));
            parameters.put("files", files);
            state.input = "[MULTIPART metadata only]";
        }
        HttpRequestLog.LOG.info("HTTP_PARAMETERS requestId={} projectId={} parameters={}",
                state.requestId, state.projectId, sanitizer.summarize(parameters));
        return true;
    }
}
