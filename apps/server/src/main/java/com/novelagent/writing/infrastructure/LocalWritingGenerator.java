package com.novelagent.writing.infrastructure;

import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ChapterReviewContent;
import com.novelagent.writing.domain.FactDecision;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.application.GeneratedManuscript;
import com.novelagent.writing.domain.ReviewIssue;
import com.novelagent.writing.domain.TypedFactPayload;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
class LocalWritingGenerator {
    ManuscriptContent manuscript(ChapterPlan chapter, ChapterContractContent contract) {
        String body = "场景从" + contract.storyTime() + "开始。" + chapter.pov() + "首先注意到周围细节里那一点不合常理的变化。\n\n"
                + chapter.coreEvent() + "。事情并没有按照最省力的方向发展，人物必须在迟疑与行动之间作出选择。"
                + "这个选择让冲突真正落到眼前，也让每一句对话都带上了尚未说破的立场。\n\n"
                + "随着场面推进，" + chapter.reveal() + "。这一信息没有直接给出答案，却改变了人物对局势的判断。"
                + "他/她重新审视此前忽略的细节，并为下一步付出具体代价。\n\n"
                + "临近结尾，" + contract.expectedExitState() + "。" + chapter.endingHook()
                + "。本地模板只用于验证流程，建议切换 Codex 或 DeepSeek 生成正式长篇正文。";
        return new ManuscriptContent(chapter.title(), body,
                "本章围绕“" + chapter.objective() + "”推进，并以新的悬念收束。",
                List.of("核对上一章退出状态", "后续审稿时验证揭示与伏笔的时序"));
    }

    GeneratedManuscript manuscript(ChapterPlan chapter, ChapterContractContent contract,
            ManuscriptContent previous, String instruction) {
        if (previous == null) {
            return new GeneratedManuscript(manuscript(chapter, contract));
        }
        if (instruction == null || instruction.isBlank()) {
            return new GeneratedManuscript(previous, List.of("未发现需要修改的内容，沿用原版本。"));
        }
        ManuscriptContent revised = new ManuscriptContent(previous.title(),
                previous.body() + "\n\n本版调整要求：" + instruction.trim(), previous.summary(),
                previous.continuityNotes());
        return new GeneratedManuscript(revised,
                List.of("根据本次写作要求补充了正文内容，标题、章节摘要和连续性备注保持不变。"));
    }

    ChapterReviewContent review(ChapterContractContent contract, ManuscriptContent manuscript) {
        return new ChapterReviewContent("正文完成了章节目标与核心揭示；本地规则审稿未发现阻断问题。",
                List.of(new ReviewIssue("I1", "INFO", "PACING", "正文篇幅低于章节建议区间，正式创作时需要扩写场景。",
                        manuscript.body().substring(0, Math.min(40, manuscript.body().length())),
                        "使用真实模型生成正式正文，补足动作、对话和场景推进。", false)),
                List.of(
                        new FactProposal("F1", "EVENT_CREATE", contract.pov(), "完成阶段目标", contract.objective(),
                                manuscript.summary(), 0.8, new TypedFactPayload(null, null,
                                contract.pov() + "完成阶段目标", contract.objective(), contract.storyTime(),
                                List.of(contract.pov()), null, null, null, null, null, null, null, null,
                                null, null, null, null, null, null, null, null, null, null, null), FactDecision.PENDING),
                        new FactProposal("F2", "STATE_CHANGE", contract.pov(), "当前目标", contract.expectedExitState(),
                                manuscript.body().substring(Math.max(0, manuscript.body().length() - 60)),
                                0.7, new TypedFactPayload("CHARACTER", contract.pov(), null, null,
                                contract.storyTime(), null, "character.current_goal", null,
                                contract.expectedExitState(), null, null, null, null, null, null, null,
                                null, null, null, null, null, null, null, null, "CHARACTER"), FactDecision.PENDING)));
    }
}
