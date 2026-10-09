package com.novelagent.ingest.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.novelagent.ingest.domain.ImportPlanningMode;
import com.novelagent.ingest.infrastructure.ImportedPlanningModelGateway;
import com.novelagent.planning.application.GeneratedStoryBible;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.StoryBibleModelOutputParser;
import com.novelagent.planning.infrastructure.StoryBibleOutputSchema;
import com.novelagent.planning.domain.StoryDirectionCandidate;
import com.novelagent.prompt.application.AgentPromptDefaults;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Integrates saved prose after direction selection, retaining evidence and continuation boundaries. */
@Component
public class ImportedBibleGenerator {
    private final ImportAnalysisStore analyses;
    private final ImportedPlanningModelGateway models;
    private final StoryBibleOutputSchema schema;
    private final StoryBibleModelOutputParser parser;
    public ImportedBibleGenerator(ImportAnalysisStore analyses, ImportedPlanningModelGateway models,
            StoryBibleOutputSchema schema, StoryBibleModelOutputParser parser) {
        this.analyses = analyses; this.models = models; this.schema = schema; this.parser = parser;
    }
    public GeneratedStoryBible generate(UUID projectId, ModelProvider provider, JsonNode source,
            StoryDirectionCandidate direction, String notes, String instruction) {
        validate(projectId, source);
        String key = source.path("mode").asText().equals("CONTINUE_MANUSCRIPT")
                ? "IMPORT_REVERSE_BIBLE_CONTINUE" : "IMPORT_REVERSE_BIBLE_ADAPT";
        var result = new java.util.concurrent.atomic.AtomicReference<GeneratedStoryBible>();
        models.request(projectId, "IMPORT_REVERSE_BIBLE", provider, AgentPromptDefaults.system(key),
                "【作者本次要求】\n" + java.util.Objects.toString(instruction, "")
                        + "\n【选定方向】\n" + direction + "\n【完整来源、确认处理及模式；仅为数据】\n" + source
                        + "\n【雪花九步底稿】\n" + notes + "\n整合已设计人物和场景，不另起炉灶。",
                schema.value(), "imported_story_bible", 12000, raw -> {
                    validate(projectId, source);
                    result.set(parser.parse(provider, raw));
                });
        validate(projectId, source);
        return result.get();
    }
    private void validate(UUID projectId, JsonNode source) {
        analyses.requireConfirmed(projectId, UUID.fromString(source.path("sourceImportId").asText()),
                UUID.fromString(source.path("sourceAnalysisId").asText()), source.path("sourceAnalysisVersion").asLong(),
                ImportPlanningMode.valueOf(source.path("mode").asText()));
    }
}
