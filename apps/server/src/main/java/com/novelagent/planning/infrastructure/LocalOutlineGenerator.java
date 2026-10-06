package com.novelagent.planning.infrastructure;

import com.novelagent.planning.application.GeneratedOutline;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.OutlineGenerator;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

@Component
public class LocalOutlineGenerator implements OutlineGenerator {
    @Override public ModelProvider provider() { return ModelProvider.LOCAL_TEMPLATE; }

    @Override
    public GeneratedOutline generate(UUID projectId, StoryBibleContent bible, OutlineWordBudget budget,
            OutlineContent previousOutline, String instruction, CreativeStrategyPolicy policy) {
        if (previousOutline != null) {
            String pacing = previousOutline.pacingStrategy();
            if (instruction != null && !instruction.isBlank()) pacing += " 本版调整：" + instruction.trim() + "。";
            return new GeneratedOutline(provider().name(), new OutlineContent(
                    previousOutline.title(), previousOutline.premise(), previousOutline.structureSummary(), pacing,
                    previousOutline.suggestedMinWords(), previousOutline.suggestedMaxWords(), previousOutline.arcs(),
                    previousOutline.readerExperiencePlans()),
                    List.of(instruction == null || instruction.isBlank()
                            ? "未发现需要修改的内容，沿用原版本。"
                            : "根据本次要求调整了全书节奏策略，卷章结构与核心事件保持不变。"));
        }
        int totalChapters = budget.recommendedChapterCount();
        int volumeCount = budget.recommendedVolumeCount();
        List<OutlineArc> arcs = new ArrayList<>();
        int nextChapter = 1;
        for (int arcIndex = 1; arcIndex <= volumeCount; arcIndex++) {
            int remainingChapters = totalChapters - nextChapter + 1;
            int remainingArcs = volumeCount - arcIndex + 1;
            int arcChapters = (int) Math.ceil(remainingChapters / (double) remainingArcs);
            List<ChapterPlan> chapters = new ArrayList<>();
            for (int offset = 0; offset < arcChapters; offset++) {
                int number = nextChapter++;
                chapters.add(chapter(number, totalChapters, bible, budget));
            }
            int arcMin = proportionalWords(budget.acceptableMinWords(), chapters.size(), totalChapters);
            int arcMax = proportionalWords(budget.acceptableMaxWords(), chapters.size(), totalChapters);
            arcs.add(new OutlineArc(arcIndex, arcTitle(arcIndex, volumeCount),
                    arcObjective(arcIndex, volumeCount, bible), bible.centralConflict(),
                    arcTurningPoint(arcIndex, volumeCount), arcOutcome(arcIndex, volumeCount),
                    arcMin, arcMax, List.copyOf(chapters)));
        }
        String pacing = "前段建立人物与规则，中段持续升级选择代价，后段集中回收因果和关系承诺。";
        if (instruction != null && !instruction.isBlank()) pacing += " 本版侧重：" + instruction.trim() + "。";
        OutlineContent content = new OutlineContent(bible.logline(), bible.logline(),
                "以人物选择推动外部事件升级，卷末必须改变目标、关系或认知状态。", pacing,
                budget.acceptableMinWords(), budget.acceptableMaxWords(), List.copyOf(arcs));
        return new GeneratedOutline(provider().name(), content);
    }

    private static ChapterPlan chapter(int number, int total, StoryBibleContent bible, OutlineWordBudget budget) {
        double progress = number / (double) total;
        String phase = progress <= .25 ? "建立局面" : progress <= .5 ? "压力升级" : progress <= .75 ? "代价显现" : "真相与抉择";
        String reveal = progress < .9 ? "揭示一条改变人物判断的新信息" : "回收关键伏笔并逼近最终真相";
        return new ChapterPlan(number, "第" + number + "章 " + phase, "主角",
                "让主角为当前目标采取不可撤销的一步", "围绕“" + bible.centralConflict() + "”推进一次具体对抗",
                reveal, number == total ? "完成核心选择，同时保留人物余韵" : "以新的风险、误解或选择收束",
                budget.recommendedChapterMinWords(), budget.recommendedChapterMaxWords());
    }

    private static String arcTitle(int index, int total) {
        if (index == 1) return "第一卷 建立与失衡";
        if (index == total) return "第" + index + "卷 真相与承担";
        return "第" + index + "卷 升级与裂变";
    }
    private static int proportionalWords(int totalWords, int chapterCount, int totalChapters) {
        return (int) Math.round(totalWords * chapterCount / (double) totalChapters / 100.0) * 100;
    }
    private static String arcObjective(int index, int total, StoryBibleContent bible) {
        if (index == 1) return "建立人物关系与世界规则，让主角主动进入核心冲突。";
        if (index == total) return "迫使主角完成最终选择，并兑现主题与人物弧光。";
        return "扩大冲突影响，让主角为阶段性胜利支付真实代价：" + bible.stakes();
    }
    private static String arcTurningPoint(int index, int total) {
        return index == total ? "主角获得真相，却必须在互相排斥的价值之间选择。" : "阶段目标表面完成，但关键关系或认知被彻底改变。";
    }
    private static String arcOutcome(int index, int total) {
        return index == total ? "核心冲突得到回答，人物关系进入新的稳定状态。" : "旧方案失效，主角带着更高代价进入下一阶段。";
    }
}
