package com.novelagent.ingest.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.ingest.domain.ImportPlanningMode;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Reads author-confirmed import inputs and rechecks the exact frozen source without generating content. */
@Service
public class ImportedPlanningSources {
    private final ImportAnalysisStore analyses;
    private final WorkImportService imports;

    public ImportedPlanningSources(ImportAnalysisStore analyses, WorkImportService imports) {
        this.analyses = analyses;
        this.imports = imports;
    }

    public Snapshot load(UUID projectId, UUID importId, UUID analysisId, long analysisVersion, ImportPlanningMode mode) {
        var analysis = requireConfirmed(projectId, importId, analysisId, analysisVersion, mode);
        return new Snapshot(analysis, imports.planningSource(projectId, importId));
    }

    /** Validate the import before inspecting a referenced run, including when no fresh source is read. */
    public void requireAccessibleImport(UUID projectId, UUID importId) {
        imports.get(projectId, importId);
    }

    /** Recheck the author's decision around long model calls without rereading the whole manuscript. */
    public JsonNode requireConfirmed(UUID projectId, UUID importId, UUID analysisId, long analysisVersion, ImportPlanningMode mode) {
        return analyses.requireConfirmed(projectId, importId, analysisId, analysisVersion, mode);
    }

    public void requireUnchanged(UUID projectId, UUID importId, JsonNode frozen, String message) {
        var current = load(projectId, importId, UUID.fromString(frozen.path("sourceAnalysisId").asText()),
                frozen.path("sourceAnalysisVersion").asLong(), ImportPlanningMode.valueOf(frozen.path("mode").asText()));
        if (!current.matches(frozen)) throw new IllegalArgumentException(message);
    }

    public record Snapshot(JsonNode analysis, WorkImportService.PlanningSource source) {
        public boolean matches(JsonNode frozen) {
            // JSONB may reload a LongNode as IntNode; compare numeric values rather than node classes.
            boolean sameAnalysis = analysis.equals((left, right) -> left.isNumber() && right.isNumber()
                    ? left.decimalValue().compareTo(right.decimalValue()) : left.equals(right) ? 0 : 1,
                    frozen.path("confirmedAnalysis"));
            return sameAnalysis && source.text().equals(frozen.path("sourceText").asText())
                    && source.chapterCount() == frozen.path("sourceChapterCount").asInt()
                    && source.truncated() == frozen.path("sourceTruncated").asBoolean();
        }
    }
}
