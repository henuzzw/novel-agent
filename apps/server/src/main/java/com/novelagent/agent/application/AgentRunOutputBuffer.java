package com.novelagent.agent.application;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class AgentRunOutputBuffer {
    public static final int MAX_CHARACTERS = 200_000;
    public record Output(String text, boolean truncated) { }
    private record Active(UUID projectId, Output output) { }
    private final Map<UUID, Active> active = new ConcurrentHashMap<>();

    public void start(UUID projectId, UUID runId) {
        active.put(runId, new Active(projectId, new Output("", false)));
    }

    public void replace(UUID runId, String text) {
        active.computeIfPresent(runId, (id, value) -> new Active(value.projectId(), bound(text)));
    }

    public Output get(UUID projectId, UUID runId) {
        var value = active.get(runId);
        return value != null && value.projectId().equals(projectId) ? value.output() : null;
    }

    public void remove(UUID runId) { active.remove(runId); }

    public static Output bound(String text) {
        if (text == null) return new Output(null, false);
        int end = Math.min(text.length(), MAX_CHARACTERS);
        if (end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return new Output(text.substring(0, end), text.length() > MAX_CHARACTERS);
    }
}
