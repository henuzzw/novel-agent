package com.novelagent.writing.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.writing.domain.ReaderExperienceEntry;
import com.novelagent.writing.domain.ReaderExperienceMemory;
import com.novelagent.writing.domain.ReaderExperienceState;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BookScanService {
    private final ProjectAccessService access;
    private final OutlineVersionRepository outlines;
    private final ReaderExperienceService readers;
    private final ObjectMapper mapper;

    public BookScanService(ProjectAccessService access, OutlineVersionRepository outlines,
            ReaderExperienceService readers, ObjectMapper mapper) {
        this.access = access; this.outlines = outlines; this.readers = readers; this.mapper = mapper;
    }

    public record Chapter(int number, String title, UUID manuscriptId, Long manuscriptRowVersion,
            UUID canonCommitId, String summary, boolean missingSummary) { }
    public record Observation(String kind, List<Integer> chapters, UUID planId, String detail) { }
    public record View(String mode, String notice, UUID outlineId, long outlineRowVersion, long canonVersion,
            String fingerprint, int plannedChapters, int canonChapters, List<Chapter> chapters,
            List<Observation> observations) { }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public View scan(UUID projectId) {
        var project = access.requireOwnedProject(projectId);
        var outline = outlines.findByIdAndProjectId(project.getCurrentOutlineVersionId(), projectId)
                .filter(value -> value.getStatus() == OutlineStatus.PUBLISHED)
                .orElseThrow(() -> new IllegalArgumentException("请先发布全书大纲"));
        return assemble(outline.getId(), outline.getRowVersion(), project.getCurrentCanonVersion(),
                outline.getContent().arcs().stream().flatMap(arc -> arc.chapters().stream()).toList(),
                readers.memory(projectId), readers.list(projectId));
    }

    View assemble(UUID outlineId, long rowVersion, long canonVersion,
            List<com.novelagent.planning.domain.ChapterPlan> plans, ReaderExperienceMemory memory,
            List<ReaderExperienceEntry> ledger) {
        var summaries = new ArrayList<ReaderExperienceMemory.Chapter>(memory.unassignedChapters());
        memory.arcs().forEach(arc -> summaries.addAll(arc.chapters()));
        var observations = new ArrayList<Observation>();
        var chapters = plans.stream().map(plan -> {
            var source = summaries.stream().filter(value -> value.chapterNumber() == plan.number()).findFirst().orElse(null);
            if (source == null) observations.add(new Observation("MISSING_CANON", List.of(plan.number()), null,
                    "没有有效正史正文，仅有未来规划；未判断该章节奏或兑现。"));
            else if (source.summary() == null || source.summary().isBlank()) observations.add(new Observation(
                    "MISSING_SUMMARY", List.of(plan.number()), null, "有效正史正文缺少摘要。"));
            return new Chapter(plan.number(), plan.title(), source == null ? null : source.manuscriptId(),
                    source == null ? null : source.manuscriptRowVersion(), source == null ? null : source.canonCommitId(),
                    source == null ? null : source.summary(), source == null || source.summary() == null || source.summary().isBlank());
        }).toList();
        for (int i = 1; i < plans.size(); i++) {
            var previous = plans.get(i - 1); var current = plans.get(i);
            if (previous.coreEvent() != null && !previous.coreEvent().isBlank()
                    && previous.coreEvent().equals(current.coreEvent())) observations.add(new Observation(
                    "IDENTICAL_PLAN_TEXT", List.of(previous.number(), current.number()), null,
                    "相邻章核心事件字段逐字相同，供作者核对；不是已证明的重复场景。"));
        }
        int lastCanon = summaries.stream().mapToInt(ReaderExperienceMemory.Chapter::chapterNumber).max().orElse(0);
        for (var entry : ledger) {
            if (entry.stale()) observations.add(new Observation("STALE_LEDGER", List.of(), entry.plan().id(), "台账证据已过期，需复核来源。"));
            else if (entry.plan().plannedChapter() != null && entry.plan().plannedChapter() <= lastCanon
                    && entry.state() != ReaderExperienceState.PAYOFF && entry.state() != ReaderExperienceState.OPEN
                    && entry.state() != ReaderExperienceState.ABANDONED) observations.add(new Observation(
                    "PAYOFF_REVIEW", List.of(entry.plan().plannedChapter()), entry.plan().id(),
                    "计划兑现章已到达，台账未登记兑现；可能尚未登记，不等同正文遗漏。"));
        }
        return new View("RULES_SUMMARY_ONLY", "仅核对当前大纲、有效正史摘要和作者台账；未读取全书完整正文，未评估文学质量。",
                outlineId, rowVersion, canonVersion, hash(List.of(outlineId, rowVersion, canonVersion, plans, memory, ledger)),
                plans.size(), summaries.size(), chapters, List.copyOf(observations));
    }

    private String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsBytes(value))); }
        catch (JsonProcessingException | NoSuchAlgorithmException exception) { throw new IllegalStateException("全书来源无法生成指纹", exception); }
    }
}
