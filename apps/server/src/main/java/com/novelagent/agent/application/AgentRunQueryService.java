package com.novelagent.agent.application;

import com.novelagent.agent.api.AgentRunResponse;
import com.novelagent.agent.api.AgentRunSummary;
import com.novelagent.agent.api.AgentRunPromptResponse;
import com.novelagent.agent.api.AgentRunOutputResponse;
import org.springframework.beans.factory.annotation.Autowired;
import com.novelagent.agent.api.AgentRunResponse.RequestSnapshotResponse;
import com.novelagent.agent.infrastructure.AgentRunQueryRepository;
import com.novelagent.project.application.ProjectAccessService;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class AgentRunQueryService {
    private final ProjectAccessService access;
    private final AgentRunQueryRepository runs;
    private final AgentRunOutputBuffer outputs;

    public AgentRunQueryService(ProjectAccessService access, AgentRunQueryRepository runs) {
        this(access, runs, new AgentRunOutputBuffer());
    }

    @Autowired
    public AgentRunQueryService(ProjectAccessService access, AgentRunQueryRepository runs, AgentRunOutputBuffer outputs) {
        this.access = access;
        this.runs = runs;
        this.outputs = outputs;
    }

    public List<AgentRunResponse> list(UUID projectId) {
        access.requireOwnedProject(projectId);
        return runs.list(projectId);
    }

    public AgentRunSummary summary(UUID projectId) {
        access.requireOwnedProject(projectId);
        return runs.summary(projectId);
    }

    public Optional<AgentRunPromptResponse> prompt(UUID projectId, UUID runId) {
        access.requireOwnedProject(projectId);
        return runs.prompt(projectId, runId);
    }

    public Optional<RequestSnapshotResponse> requestSnapshot(UUID projectId, UUID runId) {
        access.requireOwnedProject(projectId);
        return runs.requestSnapshot(projectId, runId);
    }

    public Optional<AgentRunOutputResponse> output(UUID projectId, UUID runId) {
        access.requireOwnedProject(projectId);
        return runs.output(projectId, runId).map(saved -> {
            var live = outputs.get(projectId, runId);
            if (live == null || !"RUNNING".equals(saved.status())) return saved;
            return new AgentRunOutputResponse(saved.id(), saved.status(), live.text(), live.truncated(),
                    saved.errorType(), saved.errorCategory(), saved.errorDetail(), saved.durationMs());
        });
    }
}
