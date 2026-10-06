package com.novelagent.planning.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.CreationPreparationSchema;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class CreationPreparationRunner {
    private final CreationPreparationStore store;
    private final StructuredModelGateway models;
    private final CreationPreparationSchema schemas;
    public CreationPreparationRunner(CreationPreparationStore store, StructuredModelGateway models, CreationPreparationSchema schemas) {
        this.store = store; this.models = models; this.schemas = schemas;
    }
    public CreationPreparationStore.View next(UUID projectId, UUID id, long version) {
        var claim = store.claim(projectId, id, version);
        try {
            String stage = switch (claim.nextStep()) { case 0 -> "WORLD"; case 1 -> "PLOT"; case 2 -> "REVIEW"; default -> throw new IllegalArgumentException("任务阶段无效"); };
            String prompt = switch (claim.nextStep()) { case 0 -> CreationPreparationPrompt.world(); case 1 -> CreationPreparationPrompt.plot(); default -> CreationPreparationPrompt.review(); };
            JsonNode schema = switch (claim.nextStep()) { case 0 -> schemas.world(); case 1 -> schemas.plot(); default -> schemas.review(); };
            String raw = models.request(projectId, "CREATION_PREPARATION_" + stage, claim.provider(), prompt,
                    store.modelInput(claim).toString(), schema, "creation_preparation_" + stage.toLowerCase(java.util.Locale.ROOT),
                    claim.nextStep() == 2 ? 8000 : 12000, CodexSessionPolicy.NEW_THREAD);
            return store.finish(claim, store.parse(raw));
        } catch (RuntimeException failure) {
            store.fail(claim, failure);
            return store.get(projectId, id);
        }
    }
    public CreationPreparationStore.View all(UUID projectId, UUID id, long version) {
        var result = next(projectId, id, version);
        for (int step = 0; step < 2 && result.task().status().equals("READY"); step++) {
            result = next(projectId, id, result.task().version());
        }
        return result;
    }
}
