package com.novelagent.platform.web;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.platform.api.ApiExceptionHandler;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class HttpRequestLoggingTest {
    private static final String PROJECT = "962f403a-b5ec-4069-b2ca-f124856d7a2d";
    private static final String BASE = "/api/v1/projects/" + PROJECT;
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(HttpRequestLog.class);
    private MockMvc mvc;
    private TestController controller;
    @BeforeEach void setup() {
        var sanitizer = new HttpLogSanitizer(new ObjectMapper());
        controller = new TestController();
        mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new HttpBodyLogAdvice(sanitizer), new ApiExceptionHandler())
                .addInterceptors(new HttpRequestLogInterceptor(sanitizer)).addFilters(new HttpRequestLogFilter()).build();
        appender.start(); logger.addAppender(appender);
    }
    @AfterEach void cleanup() { logger.detachAppender(appender); appender.stop(); MDC.clear(); }
    private String logs() { return appender.list.stream().map(ILoggingEvent::getFormattedMessage).collect(java.util.stream.Collectors.joining("\n")); }

    @Test void recordsParametersBodiesResponseProjectAndCorrelationWithoutChangingPayload() throws Exception {
        MDC.put("requestId", "outer"); MDC.put("projectId", "outerProject");
        var result = mvc.perform(post(BASE + "/echo").contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer supersecret").header("Idempotency-Key", "public-key")
                .param("version", "2").content("{\"provider\":\"LOCAL_CODEX\",\"body\":\"private manuscript\",\"apiKey\":\"private key\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.body").value("private manuscript")).andReturn();
        String id = result.getResponse().getHeader("X-Request-Id");
        assertThat(id).isNotBlank().isNotEqualTo("outer");
        assertThat(logs()).contains("HTTP_START", "HTTP_PARAMETERS", "HTTP_INPUT", "HTTP_END",
                "projectId=" + PROJECT, "requestId=" + id, "LOCAL_CODEX", "status=200", "version", "durationMs=")
                .doesNotContain("private manuscript", "private key", "supersecret");
        assertThat(MDC.get("requestId")).isEqualTo("outer"); assertThat(MDC.get("projectId")).isEqualTo("outerProject");
        assertThat(appender.list.stream().filter(e -> e.getFormattedMessage().startsWith("HTTP_END")).count()).isEqualTo(1);
    }
    @Test void logsHandledErrorsAndMalformedBodiesWithoutRawSource() throws Exception {
        mvc.perform(get(BASE + "/fail")).andExpect(status().isBadRequest());
        assertThat(logs()).contains("status=400", "INVALID_REQUEST").doesNotContain("private source");
        appender.list.clear();
        mvc.perform(post(BASE + "/echo").contentType(MediaType.APPLICATION_JSON).content("{private source"))
                .andExpect(status().isBadRequest());
        assertThat(logs()).contains("HTTP_END", "status=400", "[NOT_READ]").doesNotContain("private source");
    }
    @Test void logsMultipartAndBinaryMetadataOnly() throws Exception {
        mvc.perform(multipart(BASE + "/upload").file(new MockMultipartFile("file", "chapter.md", "text/markdown",
                "private uploaded source".getBytes(java.nio.charset.StandardCharsets.UTF_8)))).andExpect(status().isOk());
        assertThat(logs()).contains("chapter.md", "bytes", "MULTIPART").doesNotContain("private uploaded source");
        appender.list.clear();
        mvc.perform(get(BASE + "/download")).andExpect(status().isOk()).andExpect(content().bytes(new byte[] {1, 2, 3}));
        assertThat(logs()).contains("[BINARY bytes=3]");
    }
    @Test void streamsRemainUnbufferedAndAreLoggedOnceOnCompletion() throws Exception {
        var result = mvc.perform(get(BASE + "/events")).andExpect(request().asyncStarted()).andReturn();
        assertThat(logs()).contains("HTTP_ASYNC_OPEN").doesNotContain("HTTP_END");
        controller.emitter.send(SseEmitter.event().data("private streamed manuscript"));
        controller.emitter.complete();
        mvc.perform(asyncDispatch(result)).andExpect(status().isOk());
        assertThat(logs()).contains("ASYNC_STREAM").doesNotContain("private streamed manuscript");
        assertThat(appender.list.stream().filter(e -> e.getFormattedMessage().startsWith("HTTP_END")).count()).isEqualTo(1);
        assertThat(MDC.get("requestId")).isNull();
    }
    @Test void filterAlsoRecordsRejectionsBeforeMvcAndRestoresMdcOnUnhandledFailure() throws Exception {
        var filter = new HttpRequestLogFilter(); var request = new MockHttpServletRequest("OPTIONS", BASE + "/echo");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((jakarta.servlet.http.HttpServletResponse) res).setStatus(403));
        assertThat(logs()).contains("HTTP_START", "HTTP_END", "status=403", PROJECT);
        appender.list.clear();
        assertThatThrownBy(() -> filter.doFilter(new MockHttpServletRequest("GET", BASE + "/fail"),
                new MockHttpServletResponse(), (req, res) -> { throw new IllegalStateException("private error"); }))
                .isInstanceOf(IllegalStateException.class);
        assertThat(logs()).contains("status=500", "errorType=IllegalStateException").doesNotContain("private error");
        assertThat(MDC.get("requestId")).isNull();
    }
    @Test void doesNotLogNonApiTraffic() throws Exception {
        new HttpRequestLogFilter().doFilter(new MockHttpServletRequest("GET", "/actuator/health"),
                new MockHttpServletResponse(), (req, res) -> { });
        assertThat(appender.list).isEmpty();
    }
    @Test void projectCreationCompletesWithNewProjectIdAndNextRequestHasNoStaleMdc() throws Exception {
        var created = mvc.perform(post("/api/v1/projects").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isCreated()).andReturn();
        assertThat(logs()).contains("HTTP_START", "projectId=-", "HTTP_END", "projectId=" + PROJECT, "status=201");
        assertThat(MDC.get("projectId")).isNull();
        appender.list.clear();
        var second = mvc.perform(get("/api/v1/settings/test")).andExpect(status().isOk()).andReturn();
        assertThat(second.getResponse().getHeader("X-Request-Id")).isNotEqualTo(created.getResponse().getHeader("X-Request-Id"));
        assertThat(logs()).contains("projectId=-").doesNotContain("projectId=" + PROJECT);
    }

    @RestController
    static class TestController {
        SseEmitter emitter;
        @PostMapping("/api/v1/projects")
        ResponseEntity<com.novelagent.project.api.ProjectResponse> create(@RequestBody Map<String, Object> input) {
            return ResponseEntity.status(201).body(new com.novelagent.project.api.ProjectResponse(
                    java.util.UUID.fromString(PROJECT), "Test", null, null, 0, 0, null, null, null, null));
        }
        @GetMapping("/api/v1/settings/test")
        Map<String, Object> settings() { return Map.of("version", 1); }
        @PostMapping("/api/v1/projects/{projectId}/echo")
        Map<String, Object> echo(@RequestBody Map<String, Object> input) { return input; }
        @GetMapping("/api/v1/projects/{projectId}/fail")
        Object fail() { throw new IllegalArgumentException("private source"); }
        @PostMapping("/api/v1/projects/{projectId}/upload")
        Map<String, Object> upload(@RequestPart("file") MultipartFile file) { return Map.of("bytes", file.getSize()); }
        @GetMapping("/api/v1/projects/{projectId}/download")
        ResponseEntity<byte[]> download() { return ResponseEntity.ok().contentType(MediaType.APPLICATION_OCTET_STREAM).body(new byte[] {1, 2, 3}); }
        @GetMapping(value = "/api/v1/projects/{projectId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
        SseEmitter events() { emitter = new SseEmitter(); return emitter; }
    }
}
