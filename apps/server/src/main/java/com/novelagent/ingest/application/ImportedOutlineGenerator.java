package com.novelagent.ingest.application;

import com.novelagent.ingest.domain.ImportPlanningMode;
import com.novelagent.ingest.infrastructure.ImportedPlanningModelGateway;
import com.novelagent.planning.application.GeneratedOutline;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.ScenePlanningGuide;
import com.novelagent.planning.application.SnowflakePlanningService;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.OutlineModelOutputParser;
import com.novelagent.planning.infrastructure.OutlineOutputSchema;
import com.novelagent.planning.infrastructure.StoryDirectionSetRepository;
import com.novelagent.project.application.CreativeStrategyGuide;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.prompt.application.AgentPromptDefaults;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Reuses the exact confirmed import and saved snowflake, never a different latest run. */
@Component
public class ImportedOutlineGenerator {
    private final StoryDirectionSetRepository directions;
    private final SnowflakePlanningService snowflake;
    private final ImportAnalysisStore analyses;
    private final ImportedPlanningModelGateway models;
    private final OutlineOutputSchema schema;
    private final OutlineModelOutputParser parser;

    public ImportedOutlineGenerator(StoryDirectionSetRepository directions, SnowflakePlanningService snowflake,
            ImportAnalysisStore analyses, ImportedPlanningModelGateway models, OutlineOutputSchema schema,
            OutlineModelOutputParser parser) {
        this.directions = directions; this.snowflake = snowflake; this.analyses = analyses;
        this.models = models; this.schema = schema; this.parser = parser;
    }

    public GeneratedOutline generate(UUID projectId, StoryBibleVersion bible, OutlineWordBudget budget,
            ModelProvider provider, OutlineContent previous, String instruction, CreativeStrategyPolicy strategy) {
        var direction = directions.findByIdAndProjectId(bible.getSourceDirectionSetId(), projectId).orElseThrow();
        var plan = snowflake.get(projectId, direction.getSourceSnowflakeId());
        var source = snowflake.input(projectId, plan.id());
        var mode = ImportPlanningMode.valueOf(source.path("mode").asText());
        Runnable check = () -> analyses.requireConfirmed(projectId, bible.getSourceImportId(),
                UUID.fromString(source.path("sourceAnalysisId").asText()), source.path("sourceAnalysisVersion").asLong(), mode);
        check.run();
        int occurred = mode == ImportPlanningMode.CONTINUE_MANUSCRIPT ? source.path("sourceChapterCount").asInt() : 0;
        String key = occurred > 0 ? "IMPORT_REVERSE_OUTLINE_CONTINUE" : "IMPORT_REVERSE_OUTLINE_ADAPT";
        String prompt = "【作者本次要求】\n" + java.util.Objects.toString(instruction, "")
                + "\n【已发布故事圣经】\n" + bible.getContent()
                + "\n【篇幅预算】\n" + budget + "\n【确切来源及作者确认处理】\n" + source
                + "\n【保存的九步底稿】\n" + plan.context()
                + "\n【调整基准；空则重新生成】\n" + previous
                + "\n续写时前 " + occurred + " 章必须忠实保留原文事件并标记 OCCURRED，其余 PLANNED；改编全部 PLANNED。"
                + "未来设计不等于已发生事实，不把已知结局提前泄露。"
                + CreativeStrategyGuide.render(strategy) + CreativeStrategyGuide.outlineRules() + ScenePlanningGuide.planningRules();
        var result = new java.util.concurrent.atomic.AtomicReference<GeneratedOutline>();
        models.request(projectId, "IMPORT_REVERSE_OUTLINE", provider, AgentPromptDefaults.system(key), prompt,
                schema.value(), "imported_outline", 16000, raw -> {
                    check.run();
                    result.set(ImportedPlanningService.normalizeChapterStatuses(parser.parse(provider, raw), occurred));
                });
        check.run();
        return result.get();
    }
}
