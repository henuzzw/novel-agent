package com.novelagent.ingest.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.ingest.api.PrepareImportedStoryRequest;
import com.novelagent.ingest.domain.ImportPlanningMode;
import com.novelagent.ingest.infrastructure.ImportedPlanningStore;
import com.novelagent.planning.application.SnowflakePlanningService;
import com.novelagent.planning.domain.SnowflakePlan;
import com.novelagent.project.api.CreativeIntentRequest;
import com.novelagent.project.api.ProjectResponse;
import com.novelagent.project.application.ProjectService;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Import -> eight required prose steps (+ optional ninth). Author confirms the exact saved run next. */
@Service
public class ImportedStoryBlueprintService {
    private final ImportedPlanningSources sources;
    private final ImportedPlanningStore store;
    private final SnowflakePlanningService snowflake;
    private final ProjectService projects;
    private final ObjectMapper mapper;

    public ImportedStoryBlueprintService(ImportedPlanningSources sources, ImportedPlanningStore store, SnowflakePlanningService snowflake,
            ProjectService projects, ObjectMapper mapper) {
        this.sources = sources;
        this.store = store;
        this.snowflake = snowflake;
        this.projects = projects;
        this.mapper = mapper;
    }

    public SnowflakePlan generate(UUID projectId, UUID importId, PrepareImportedStoryRequest request) {
        return prepare(projectId, importId, request, null);
    }

    /** Resume the exact imported run, never implicitly reuse an unrelated latest draft. */
    public SnowflakePlan resume(UUID projectId, UUID importId, UUID planId) {
        sources.requireAccessibleImport(projectId, importId);
        var plan = snowflake.get(projectId, planId);
        if (!planId.equals(snowflake.latest(projectId).orElseThrow().id())) {
            throw new IllegalArgumentException("已有更新的雪花规划，请刷新后操作最新记录");
        }
        var input = snowflake.input(projectId, planId);
        if (!importId.toString().equals(input.path("sourceImportId").asText())) {
            throw new IllegalArgumentException("该雪花底稿不属于当前导入，不能继续");
        }
        if (!java.util.Set.of("FAILED", "CANCELLED", "SUCCEEDED").contains(plan.status())) {
            throw new IllegalArgumentException("该雪花规划正在生成，不能重复继续");
        }
        var request = new PrepareImportedStoryRequest(plan.provider(),
                ImportPlanningMode.valueOf(input.path("mode").asText()),
                input.path("authorInstruction").asText(""), UUID.fromString(input.path("sourceAnalysisId").asText()),
                input.path("sourceAnalysisVersion").asLong(), input.path("targetWords").asInt(),
                input.path("expandScenes").asBoolean(false));
        sources.requireUnchanged(projectId, importId, input, "原文或已确认解析已改变，请重新生成雪花底稿");
        return prepare(projectId, importId, request, planId);
    }

    private SnowflakePlan prepare(UUID projectId, UUID importId, PrepareImportedStoryRequest request, UUID resumeId) {
        var source = sources.load(projectId, importId, request.analysisId(), request.analysisVersion(), request.mode());
        var initialProject = projects.get(projectId);
        store.claim(projectId, importId, request.mode(), resumeId != null);
        try {
            var input = input(importId, request, source, initialProject);
            var plan = resumeId == null ? snowflake.generate(projectId, request.provider(), input)
                    : snowflake.resume(projectId, resumeId);
            sources.requireConfirmed(projectId, importId, request.analysisId(), request.analysisVersion(), request.mode());
            updateIntent(projectId, initialProject.creativeIntent(), plan, request.targetWords());
            sources.requireConfirmed(projectId, importId, request.analysisId(), request.analysisVersion(), request.mode());
            store.ready(projectId, importId);
            return plan;
        } catch (RuntimeException error) {
            try {
                store.failed(projectId, importId, error);
            } catch (RuntimeException saveFailure) {
                error.addSuppressed(saveFailure);
            }
            throw error;
        }
    }

    private ObjectNode input(UUID importId,
            PrepareImportedStoryRequest request, ImportedPlanningSources.Snapshot snapshot, ProjectResponse project) {
        var source = snapshot.source();
        var input = mapper.createObjectNode();
        input.put("mode", request.mode().name()).put("sourceImportId", importId.toString())
                .put("sourceAnalysisId", request.analysisId().toString()).put("sourceAnalysisVersion", request.analysisVersion())
                .put("authorInstruction", Objects.toString(request.instruction(), ""))
                .put("sourceText", source.text()).put("sourceChapterCount", source.chapterCount()).put("sourceTruncated", source.truncated())
                .put("targetWords", request.targetWords()).put("expandScenes", request.expandScenes());
        input.set("intent", mapper.valueToTree(project.creativeIntent()));
        input.set("confirmedAnalysis", snapshot.analysis());
        return input;
    }

    private void updateIntent(UUID projectId, ProjectResponse.CreativeIntentResponse intent, SnowflakePlan plan, int targetWords) {
        if (intent == null) {
            projects.updateCreativeIntent(projectId, 0, new CreativeIntentRequest(plan.core(), List.of(), null,
                    summary(plan.steps().get("CHARACTER_ARCS")), plan.core(), List.of(), targetWords,
                    null, List.of(), List.of(), List.of()));
        } else if (!Objects.equals(intent.targetWords(), targetWords)) {
            projects.updateCreativeIntent(projectId, intent.version(), new CreativeIntentRequest(intent.premise(), intent.genres(),
                    intent.targetAudience(), intent.protagonistBrief(), intent.centralConflict(), intent.tones(), targetWords,
                    intent.endingPreference(), intent.mustHave(), intent.avoid(), intent.stylePreferences()));
        }
    }

    private static String summary(String value) {
        return value == null ? "" : value.substring(0, Math.min(2000, value.length()));
    }
}
