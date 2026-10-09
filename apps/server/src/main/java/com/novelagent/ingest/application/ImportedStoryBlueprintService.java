package com.novelagent.ingest.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.ingest.api.PrepareImportedStoryRequest;
import com.novelagent.planning.api.StoryDirectionSetResponse;
import com.novelagent.planning.application.SnowflakePlanningService;
import com.novelagent.planning.application.StoryDirectionService;
import com.novelagent.planning.domain.CreativeIntentSnapshot;
import com.novelagent.project.api.CreativeIntentRequest;
import com.novelagent.project.application.ProjectService;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Import -> eight required prose steps (+ optional ninth) -> directions. Author selects next. */
@Service
public class ImportedStoryBlueprintService {
    private final WorkImportService imports;
    private final ImportAnalysisStore analyses;
    private final SnowflakePlanningService snowflake;
    private final StoryDirectionService directions;
    private final ProjectService projects;
    private final ObjectMapper mapper;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;
    public ImportedStoryBlueprintService(WorkImportService imports, ImportAnalysisStore analyses,
            SnowflakePlanningService snowflake, StoryDirectionService directions, ProjectService projects, ObjectMapper mapper,
            org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.imports = imports; this.analyses = analyses; this.snowflake = snowflake;
        this.directions = directions; this.projects = projects; this.mapper = mapper;
        this.jdbc = jdbc;
    }
    public StoryDirectionSetResponse generate(UUID projectId, UUID importId, PrepareImportedStoryRequest request) {
        var confirmed = analyses.requireConfirmed(projectId, importId, request.analysisId(), request.analysisVersion(), request.mode());
        var source = imports.planningSource(projectId, importId);
        var initialProject = projects.get(projectId);
        if (jdbc.update("UPDATE work_import SET planning_status = 'GENERATING', planning_mode = ?, planning_error = NULL"
                + " WHERE project_id = ? AND id = ? AND planning_status <> 'GENERATING'",
                request.mode().name(), projectId, importId) != 1) {
            throw new IllegalArgumentException("该导入的雪花规划正在生成，请勿重复启动");
        }
        try {
        var input = mapper.createObjectNode();
        input.put("mode", request.mode().name()).put("sourceImportId", importId.toString())
                .put("sourceAnalysisId", request.analysisId().toString()).put("sourceAnalysisVersion", request.analysisVersion())
                .put("authorInstruction", java.util.Objects.toString(request.instruction(), ""))
                .put("sourceText", source.text()).put("sourceChapterCount", source.chapterCount()).put("sourceTruncated", source.truncated())
                .put("targetWords", request.targetWords()).put("expandScenes", request.expandScenes());
        input.set("intent", mapper.valueToTree(initialProject.creativeIntent()));
        input.set("confirmedAnalysis", confirmed);
        var plan = snowflake.generate(projectId, request.provider(), input);
        analyses.requireConfirmed(projectId, importId, request.analysisId(), request.analysisVersion(), request.mode());
        var snapshot = new CreativeIntentSnapshot(plan.core(), List.of(), null,
                summary(plan.steps().get("CHARACTER_ARCS")), plan.core(), List.of(), request.targetWords(),
                null, List.of(), List.of(), List.of(), 0);
        if (initialProject.creativeIntent() == null) {
            projects.updateCreativeIntent(projectId, 0, new CreativeIntentRequest(snapshot.premise(), snapshot.genres(),
                    snapshot.targetAudience(), snapshot.protagonistBrief(), snapshot.centralConflict(), snapshot.tones(),
                    snapshot.targetWords(), snapshot.endingPreference(), snapshot.mustHave(), snapshot.avoid(), snapshot.stylePreferences()));
        } else snapshot = new CreativeIntentSnapshot(snapshot.premise(), snapshot.genres(), snapshot.targetAudience(),
                snapshot.protagonistBrief(), snapshot.centralConflict(), snapshot.tones(), request.targetWords(),
                initialProject.creativeIntent().endingPreference(), initialProject.creativeIntent().mustHave(),
                initialProject.creativeIntent().avoid(), initialProject.creativeIntent().stylePreferences(),
                initialProject.creativeIntent().version());
        if (initialProject.creativeIntent() != null && !java.util.Objects.equals(initialProject.creativeIntent().targetWords(), request.targetWords())) {
            var intent = initialProject.creativeIntent();
            projects.updateCreativeIntent(projectId, intent.version(), new CreativeIntentRequest(intent.premise(), intent.genres(),
                    intent.targetAudience(), intent.protagonistBrief(), intent.centralConflict(), intent.tones(), request.targetWords(),
                    intent.endingPreference(), intent.mustHave(), intent.avoid(), intent.stylePreferences()));
        }
        String modeRules = request.mode().name().equals("CONTINUE_MANUSCRIPT")
                ? "续写保留原文已发生事实，不把候选方向当作重写授权。" : "改编只在已确认处理范围内重构。";
        var result = directions.generateFromSnowflake(projectId, request.provider(), snapshot,
                modeRules + "\n作者要求：" + java.util.Objects.toString(request.instruction(), "") + "\n" + plan.context(), plan.id());
        analyses.requireConfirmed(projectId, importId, request.analysisId(), request.analysisVersion(), request.mode());
        jdbc.update("UPDATE work_import SET planning_status = 'DIRECTIONS_READY' WHERE project_id = ? AND id = ?", projectId, importId);
        return result;
        } catch (RuntimeException error) {
            String message = java.util.Objects.toString(error.getMessage(), error.getClass().getSimpleName());
            jdbc.update("UPDATE work_import SET planning_status = ?, planning_error = ? WHERE project_id = ? AND id = ?",
                    error instanceof com.novelagent.agent.application.GenerationStoppedException ? "CANCELLED" : "FAILED",
                    message.substring(0, Math.min(1000, message.length())), projectId, importId);
            throw error;
        }
    }
    private static String summary(String value) {
        return value == null ? "" : value.substring(0, Math.min(2000, value.length()));
    }
}
