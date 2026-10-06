package com.novelagent.ingest.application;

import com.novelagent.ingest.api.ReversePlanResponse;
import com.novelagent.ingest.domain.ImportPlanningMode;
import com.novelagent.planning.api.OutlineResponse;
import com.novelagent.planning.api.StoryBibleResponse;
import com.novelagent.planning.application.GeneratedOutline;
import com.novelagent.planning.application.GeneratedStoryBible;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class ImportedPlanningDraftStore {
    private final StoryBibleVersionRepository bibles;
    private final OutlineVersionRepository outlines;
    private final JdbcTemplate jdbc;
    private final ImportAnalysisStore analyses;

    ImportedPlanningDraftStore(StoryBibleVersionRepository bibles, OutlineVersionRepository outlines,
            JdbcTemplate jdbc, ImportAnalysisStore analyses) {
        this.bibles = bibles;
        this.outlines = outlines;
        this.jdbc = jdbc;
        this.analyses = analyses;
    }

    @Transactional
    ReversePlanResponse save(UUID projectId, UUID importId, ImportPlanningMode mode, String instruction,
            GeneratedStoryBible generatedBible, OutlineWordBudget budget, GeneratedOutline generatedOutline, UUID analysisId, Long analysisVersion) {
        analyses.lock(projectId);
        analyses.requireConfirmed(projectId, importId, analysisId, analysisVersion, mode);
        int bibleGeneration = bibles.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)
                .map(value -> value.getGenerationNumber() + 1).orElse(1);
        StoryBibleVersion bible = StoryBibleVersion.createFromImport(UUID.randomUUID(), projectId,
                bibleGeneration, generatedBible.generatorType(), instruction, importId, generatedBible.content());
        bible = bibles.saveAndFlush(bible);

        int outlineGeneration = outlines.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)
                .map(value -> value.getGenerationNumber() + 1).orElse(1);
        OutlineVersion outline = OutlineVersion.create(UUID.randomUUID(), projectId, outlineGeneration,
                generatedOutline.generatorType(), instruction, bible.getId(), budget, generatedOutline.content());
        outline = outlines.saveAndFlush(outline);
        jdbc.update("""
                UPDATE work_import SET planning_status = 'GENERATED', planning_mode = ?, planning_error = NULL,
                    generated_bible_version_id = ?, generated_outline_version_id = ?, generated_analysis_id = ?, generated_analysis_version = ? WHERE id = ?
                """, mode.name(), bible.getId(), outline.getId(), analysisId, analysisVersion, importId);
        return new ReversePlanResponse(StoryBibleResponse.from(bible), OutlineResponse.from(outline));
    }
}
