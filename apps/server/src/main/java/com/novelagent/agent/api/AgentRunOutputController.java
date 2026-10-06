package com.novelagent.agent.api;

import com.novelagent.agent.application.AgentRunQueryService;
import com.novelagent.agent.application.AgentRunStreamService;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/api/v1/projects/{projectId}/agent-runs/{runId}")
public class AgentRunOutputController {
    private final AgentRunQueryService queries;
    private final AgentRunStreamService streams;

    public AgentRunOutputController(AgentRunQueryService queries, AgentRunStreamService streams) {
        this.queries = queries; this.streams = streams;
    }

    @GetMapping("/response")
    public ResponseEntity<AgentRunOutputResponse> response(@PathVariable UUID projectId, @PathVariable UUID runId) {
        return queries.output(projectId, runId).map(value -> ResponseEntity.ok()
                .cacheControl(CacheControl.noStore()).body(value)).orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> events(@PathVariable UUID projectId, @PathVariable UUID runId) {
        return streams.open(projectId, runId).map(value -> ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .header("X-Accel-Buffering", "no").body(value)).orElseGet(() -> ResponseEntity.notFound().build());
    }
}
