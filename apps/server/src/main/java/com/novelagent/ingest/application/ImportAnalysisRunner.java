package com.novelagent.ingest.application;

import com.novelagent.ingest.infrastructure.ImportAnalysisPrompt;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

@Service
public class ImportAnalysisRunner {
    private final ImportAnalysisStore store;
    private final StructuredModelGateway models;
    private final ImportAnalysisPrompt prompt;
    public ImportAnalysisRunner(ImportAnalysisStore store, StructuredModelGateway models, ImportAnalysisPrompt prompt) {
        this.store = store; this.models = models; this.prompt = prompt;
    }
    public ImportAnalysisStore.View next(UUID projectId, UUID importId, UUID id, long version) {
        var claim = store.claim(projectId, importId, id, version);
        try {
            var result = new AtomicReference<ImportAnalysisStore.View>();
            models.request(projectId, "IMPORT_SOURCE_ANALYSIS", claim.report().provider(), ImportAnalysisPrompt.SYSTEM,
                    claim.input(), prompt.schema(), "import_source_analysis", 12000, CodexSessionPolicy.NEW_THREAD,
                    raw -> result.set(store.finish(claim, raw)));
            return result.get();
        } catch (RuntimeException error) {
            store.fail(claim, error); return store.get(projectId, importId, id);
        }
    }
}
