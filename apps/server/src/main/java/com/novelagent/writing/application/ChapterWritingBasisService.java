package com.novelagent.writing.application;

import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ManuscriptBasis;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.infrastructure.ChapterContractVersionRepository;
import java.util.List;


/** Resolves the published chapter plan once; old contracts are read only for legacy provenance. */
public final class ChapterWritingBasisService {
    private ChapterWritingBasisService() { }

    public static ManuscriptBasis capture(WritingContextService.Context context) {
        ChapterPlan chapter = context.chapter();
        // Reuse the existing prompt payload shape, without creating any contract record or model stage.
        ChapterContractContent plan = new ChapterContractContent(chapter.title(), chapter.pov(), chapter.objective(),
                "以已确认圣经、大纲与前文为准；未知时间不得编成事实", List.of(),
                requiredBeats(chapter), entries(chapter.reveal()), List.of(),
                "本章行动的后果按已发布大纲落实，不提前兑现未来计划", context.outline().getContent().readerExperiencePlans().stream()
                        .filter(seed -> java.util.Objects.equals(seed.plannedChapter(), chapter.number()))
                        .map(seed -> seed.title() + "；承诺：" + seed.promise() + "；埋设：" + seed.setup()
                                + "；预期回收（未来计划，不是已发生）：" + seed.payoff()).toList(), chapter.endingHook(),
                chapter.suggestedMinWords(), chapter.suggestedMaxWords());
        return new ManuscriptBasis(context.outline().getId(), context.boundaryFingerprint(), plan);
    }

    public static ManuscriptBasis requireCurrent(ManuscriptVersion manuscript, WritingContextService.Context context,
            ChapterContractVersionRepository legacyContracts) {
        ManuscriptBasis current = capture(context);
        if (manuscript.getWritingBasis() != null) {
            if (!current.equals(manuscript.getWritingBasis())) {
                throw new IllegalArgumentException("正文的圣经、大纲、策略或创作准备已更新，请先调整正文");
            }
        } else {
            var legacy = legacyContracts.findByIdAndProjectId(manuscript.getSourceContractVersionId(), manuscript.getProjectId())
                    .orElseThrow(() -> new IllegalArgumentException("历史正文缺少可追溯的写作来源"));
            if (!current.outlineId().equals(legacy.getSourceOutlineVersionId())) {
                throw new IllegalArgumentException("历史正文来自旧大纲，请先调整正文");
            }
        }
        return current;
    }

    private static List<String> entries(String text) {
        return text == null || text.isBlank() ? List.of() : List.of(text);
    }

    private static List<String> requiredBeats(ChapterPlan chapter) {
        var beats = new java.util.ArrayList<>(entries(chapter.coreEvent()));
        if (!chapter.sceneOutlineNeedsUpdate() && chapter.sceneOutline() != null && !chapter.sceneOutline().isBlank()) {
            beats.add("【已保存场景底稿；未来规划不是正史】\n" + chapter.sceneOutline());
        }
        return List.copyOf(beats);
    }
}
