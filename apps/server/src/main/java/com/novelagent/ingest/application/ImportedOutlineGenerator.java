package com.novelagent.ingest.application;

import com.novelagent.ingest.domain.ImportPlanningMode;
import com.novelagent.ingest.infrastructure.ImportedPlanningModelGateway;
import com.novelagent.planning.application.GeneratedOutline;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.ScenePlanningGuide;
import com.novelagent.planning.application.SnowflakePlanningService;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.ChapterPlanStatus;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.OutlineModelOutputParser;
import com.novelagent.planning.infrastructure.OutlineOutputSchema;
import com.novelagent.project.application.CreativeStrategyGuide;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.prompt.application.AgentPromptDefaults;
import java.util.UUID;
import java.util.List;
import java.util.ArrayList;
import org.springframework.stereotype.Component;

/** Reuses the exact confirmed import and saved snowflake, never a different latest run. */
@Component
public class ImportedOutlineGenerator {
    private final SnowflakePlanningService snowflake;
    private final ImportAnalysisStore analyses;
    private final ImportedPlanningModelGateway models;
    private final OutlineOutputSchema schema;
    private final OutlineModelOutputParser parser;

    public ImportedOutlineGenerator(SnowflakePlanningService snowflake,
            ImportAnalysisStore analyses, ImportedPlanningModelGateway models, OutlineOutputSchema schema,
            OutlineModelOutputParser parser) {
        this.snowflake = snowflake; this.analyses = analyses;
        this.models = models; this.schema = schema; this.parser = parser;
    }

    public GeneratedOutline generate(UUID projectId, StoryBibleVersion bible, OutlineWordBudget budget,
            ModelProvider provider, OutlineContent previous, String instruction, CreativeStrategyPolicy strategy) {
        var plan = snowflake.get(projectId, bible.getSourceSnowflakeId());
        var source = snowflake.input(projectId, plan.id());
        var mode = ImportPlanningMode.valueOf(source.path("mode").asText());
        Runnable check = () -> analyses.requireConfirmed(projectId, bible.getSourceImportId(),
                UUID.fromString(source.path("sourceAnalysisId").asText()), source.path("sourceAnalysisVersion").asLong(), mode);
        check.run();
        int occurred = mode == ImportPlanningMode.CONTINUE_MANUSCRIPT ? source.path("sourceChapterCount").asInt() : 0;
        String key = occurred > 0 ? "IMPORT_REVERSE_OUTLINE_CONTINUE" : "IMPORT_REVERSE_OUTLINE_ADAPT";
        String notes = plan.context();
        String prompt = "【作者本次要求】\n" + java.util.Objects.toString(instruction, "")
                + "\n【已确认雪花资料投影】\n" + bible.getContent().withoutRepeatedDevelopmentNotes(notes)
                + "\n【篇幅预算】\n" + budget + "\n【确切来源及作者确认处理】\n" + source
                + "\n【保存的九步底稿】\n" + notes
                + "\n【调整基准；空则重新生成】\n" + previous
                + "\n续写时前 " + occurred + " 章必须忠实保留原文事件并标记 OCCURRED，其余 PLANNED；改编全部 PLANNED。"
                + "未来设计不等于已发生事实，不把已知结局提前泄露。"
                + CreativeStrategyGuide.render(strategy) + CreativeStrategyGuide.outlineRules(strategy) + ScenePlanningGuide.planningRules();
        var result = new java.util.concurrent.atomic.AtomicReference<GeneratedOutline>();
        models.request(projectId, "IMPORT_REVERSE_OUTLINE", provider, AgentPromptDefaults.system(key), prompt,
                schema.value(), "imported_outline", 16000, raw -> {
                    check.run();
                    result.set(normalizeChapterStatuses(parser.parse(provider, raw), occurred));
                });
        check.run();
        return result.get();
    }

    /** Imported chapters are occurred facts only in continuation mode, never model-decided statuses. */
    static GeneratedOutline normalizeChapterStatuses(GeneratedOutline generated, int occurredChapterCount) {
        var content = generated.content();
        int chapterIndex = 0;
        List<OutlineArc> arcs = new ArrayList<>();
        for (var arc : content.arcs()) {
            List<ChapterPlan> chapters = new ArrayList<>();
            for (var chapter : arc.chapters()) {
                chapters.add(chapter.withStatus(++chapterIndex <= occurredChapterCount
                        ? ChapterPlanStatus.OCCURRED : ChapterPlanStatus.PLANNED));
            }
            arcs.add(new OutlineArc(arc.ordinal(), arc.title(), arc.objective(), arc.mainConflict(),
                    arc.turningPoint(), arc.outcome(), arc.suggestedMinWords(), arc.suggestedMaxWords(), List.copyOf(chapters)));
        }
        if (chapterIndex < occurredChapterCount) throw new IllegalArgumentException("反推大纲章节数少于已导入章节数");
        return new GeneratedOutline(generated.generatorType(), new OutlineContent(content.title(), content.premise(),
                content.structureSummary(), content.pacingStrategy(), content.suggestedMinWords(),
                content.suggestedMaxWords(), List.copyOf(arcs), content.readerExperiencePlans()));
    }
}
