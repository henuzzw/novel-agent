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
        if (chapter.contentNeedsUpdate()) throw new IllegalArgumentException("本章规划依据已变化，请先更新章节规划文字块");
        // Reuse the existing prompt payload shape, without creating any contract record or model stage.
        int count = context.arc().chapters().size();
        // Numeric defaults are a capacity reference; explicit words/POV in the text block take precedence.
        ChapterContractContent plan = new ChapterContractContent(chapter.title(), "按本章规划文字块指定的视角", "落实本章规划文字块",
                "以已确认圣经、大纲与前文为准；未知时间不得编成事实", List.of(),
                List.of("【本章完整规划；未来计划不是正史】\n" + chapter.content()), List.of(), List.of(),
                "本章行动的后果按已发布大纲落实，不提前兑现未来计划", context.outline().getContent().readerExperiencePlans().stream()
                        .filter(seed -> java.util.Objects.equals(seed.plannedChapter(), chapter.number()))
                        .map(seed -> seed.title() + "；承诺：" + seed.promise() + "；埋设：" + seed.setup()
                                + "；预期回收（未来计划，不是已发生）：" + seed.payoff()).toList(), "按本章规划文字块的结果与章末期待",
                Math.max(1, context.arc().suggestedMinWords() / count),
                Math.max(1, context.arc().suggestedMaxWords() / count));
        return new ManuscriptBasis(context.outline().getId(), context.boundaryFingerprint(), plan);
    }

    public static ManuscriptBasis requireCurrent(ManuscriptVersion manuscript, WritingContextService.Context context,
            ChapterContractVersionRepository legacyContracts) {
        ManuscriptBasis current = capture(context);
        if (manuscript.getWritingBasis() != null) {
            if (!current.equals(manuscript.getWritingBasis())) {
                throw new IllegalArgumentException("正文关联的规划资料、大纲、创作策略或创作边界已变化，请先调整正文");
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

}
