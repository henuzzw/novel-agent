package com.novelagent.planning.application;

import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.ChapterPlanStatus;
import com.novelagent.planning.domain.CreationPreparation;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CreationPreparationApprovalService {
    private final CreationPreparationStore store;
    private final PlanningMaterialSyncService materials;
    private final OutlineVersionRepository outlines;
    private final JdbcTemplate jdbc;
    private final com.novelagent.canon.application.CharacterNameService names;
    public CreationPreparationApprovalService(CreationPreparationStore store, PlanningMaterialSyncService materials,
            OutlineVersionRepository outlines, JdbcTemplate jdbc, com.novelagent.canon.application.CharacterNameService names) {
        this.store = store; this.materials = materials; this.outlines = outlines; this.jdbc = jdbc; this.names = names;
    }
    public record Confirm(long version, boolean authorConfirmed, boolean acceptWarnings, List<Integer> selectedChapters) { }
    @Transactional
    public CreationPreparationStore.View confirm(UUID projectId, UUID id, Confirm input) {
        if (input == null || !input.authorConfirmed()) throw new IllegalArgumentException("请明确确认创作资料或复核报告");
        store.ownAndLock(projectId); var task = store.require(projectId, id);
        CreationPreparationStore.version(task, input.version()); store.fresh(task);
        if (!task.status().equals("AWAITING_CONFIRMATION") || task.reviewReport() == null) throw new IllegalArgumentException("请先完成一致性复核");
        if (task.reviewReport().issues().stream().anyMatch(item -> item.severity().equals("BLOCKING"))) throw new IllegalArgumentException("存在阻断问题，请修改规划并重新复核");
        if (!task.reviewReport().issues().isEmpty() && !input.acceptWarnings()) throw new IllegalArgumentException("请明确接受待关注问题，或修改后重新复核");
        UUID resultOutline = null;
        if (task.mode().equals("PREPARE")) {
            if (input.selectedChapters() != null && !input.selectedChapters().isEmpty()) throw new IllegalArgumentException("创作准备不能直接调整大纲");
            materials.syncPreparation(projectId, id, task.worldDesign(), task.plotDesign());
            jdbc.update("INSERT INTO creation_preparation_current(project_id, task_id) VALUES (?, ?) ON CONFLICT(project_id) DO UPDATE SET task_id = EXCLUDED.task_id, updated_at = now()", projectId, id);
        } else {
            List<Integer> selected = input.selectedChapters() == null ? List.of() : input.selectedChapters();
            if (Set.copyOf(selected).size() != selected.size() || selected.stream().anyMatch(chapter -> task.reviewReport().adjustments().stream().noneMatch(item -> item.chapterNumber() == chapter))) {
                throw new IllegalArgumentException("选定调整章节不存在或重复");
            }
            if (!selected.isEmpty()) resultOutline = createFutureDraft(task, selected);
            for (var link : task.reviewReport().planLinks()) {
                jdbc.update("""
                        INSERT INTO chapter_plan_link(id, project_id, plan_id, source_commit_id, source_fact_id, plan_row_version, proposed_state, evidence, evidence_tokenized)
                        SELECT ?, ?, ?, f.source_commit_id, f.id, p.row_version, ?, ?, ? FROM story_fact f
                        JOIN reader_experience_plan p ON p.id = ? AND p.project_id = f.project_id
                        JOIN canon_commit c ON c.id = f.source_commit_id WHERE f.id = ? AND f.project_id = ? AND c.active AND f.canon_version_to IS NULL
                        ON CONFLICT DO NOTHING
                        """,
                        UUID.randomUUID(), projectId, UUID.fromString(link.planId()), link.state(), link.evidence(), names.tokenize(projectId, link.evidence()),
                        UUID.fromString(link.planId()), UUID.fromString(link.factId()), projectId);
            }
        }
        jdbc.update("UPDATE creation_preparation_task SET status = 'CONFIRMED', result_outline_id = ?, row_version = row_version + 1, updated_at = now() WHERE id = ?", resultOutline, id);
        return store.get(projectId, id);
    }
    private UUID createFutureDraft(CreationPreparationStore.Task task, List<Integer> selected) {
        var base = outlines.findByIdAndProjectId(task.sourceOutlineId(), task.projectId()).orElseThrow();
        int last = task.sourceSnapshot().path("lastCanonChapter").asInt();
        var arcs = base.getContent().arcs().stream().map(arc -> new OutlineArc(arc.ordinal(), arc.title(), arc.objective(),
                arc.mainConflict(), arc.turningPoint(), arc.outcome(), arc.suggestedMinWords(), arc.suggestedMaxWords(),
                arc.chapters().stream().map(chapter -> adjusted(chapter, task.reviewReport(), selected, last)).toList())).toList();
        var content = base.getContent();
        var revised = new OutlineContent(content.title(), content.premise(), content.structureSummary(), content.pacingStrategy(),
                content.suggestedMinWords(), content.suggestedMaxWords(), arcs, content.readerExperiencePlans());
        int generation = outlines.findFirstByProjectIdOrderByGenerationNumberDesc(task.projectId()).map(value -> value.getGenerationNumber() + 1).orElse(1);
        var draft = OutlineVersion.create(UUID.randomUUID(), task.projectId(), generation, "CREATION_REVIEW", task.instruction(),
                task.sourceBibleId(), base.getId(), base.getWordBudget(), revised,
                task.reviewReport().adjustments().stream().filter(item -> selected.contains(item.chapterNumber())).map(item -> "第" + item.chapterNumber() + "章：" + item.reason()).toList());
        return outlines.saveAndFlush(draft).getId();
    }
    private ChapterPlan adjusted(ChapterPlan chapter, CreationPreparation.Review review, List<Integer> selected, int last) {
        if (!selected.contains(chapter.number())) return chapter;
        if (chapter.number() <= last || chapter.status() == ChapterPlanStatus.OCCURRED) throw new IllegalArgumentException("不能调整已发生章节");
        var change = review.adjustments().stream().filter(item -> item.chapterNumber() == chapter.number()).findFirst().orElseThrow();
        return new ChapterPlan(chapter.number(), chapter.title(), chapter.pov(), change.objective(), change.coreEvent(),
                change.reveal(), change.endingHook(), chapter.suggestedMinWords(), chapter.suggestedMaxWords(), chapter.status());
    }
}
