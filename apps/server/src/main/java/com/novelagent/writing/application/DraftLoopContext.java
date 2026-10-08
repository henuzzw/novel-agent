package com.novelagent.writing.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.AgentStage;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.memory.application.ContextBudgetPlanner;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.platform.support.Sha256;
import com.novelagent.project.application.CreativeStrategyGuide;
import com.novelagent.writing.domain.DraftLoopRun;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** 冻结 A/B/C 共用资料；每次写入前重新核对来源，不在锁内等待模型或检索。 */
@Service
public class DraftLoopContext {
    public record Source(DraftLoopRun.Basis basis, WritingContextService.Context context, ManuscriptVersion latest) { }
    private final WritingContextService contexts;
    private final CharacterNameService names;
    private final CharacterProfileService profiles;
    private final WritingStyleService styles;
    private final ManuscriptVersionRepository manuscripts;
    private final ContextBudgetPlanner budgets;
    private final ObjectMapper mapper;

    public DraftLoopContext(WritingContextService contexts, CharacterNameService names, CharacterProfileService profiles,
            WritingStyleService styles, ManuscriptVersionRepository manuscripts, ContextBudgetPlanner budgets, ObjectMapper mapper) {
        this.contexts = contexts; this.names = names; this.profiles = profiles; this.styles = styles;
        this.manuscripts = manuscripts; this.budgets = budgets; this.mapper = mapper;
    }

    public Source capture(UUID projectId, int chapter) {
        var context = contexts.context(projectId, chapter);
        var project = contexts.requireOwnedProject(projectId);
        var data = mapper.createObjectNode();
        data.put("projectName", project.getName());
        data.put("chapterNumber", chapter);
        data.set("bible", mapper.valueToTree(context.bible().getContent()));
        data.set("chapter", mapper.valueToTree(context.chapter()));
        data.set("arc", mapper.valueToTree(context.arc()));
        data.set("chapterPlan", mapper.valueToTree(ChapterWritingBasisService.capture(context).plan()));
        data.put("characterProfiles", profiles.promptContext(projectId));
        data.put("confirmedPreparation", context.preparationContext());
        data.put("selectedStyle", styles.promptContext(projectId));
        data.put("strategy", CreativeStrategyGuide.render(context.creativeStrategy()));
        data.put("futurePlanOnly", context.futureContext());
        var previous = data.putArray("previousAcceptedReferenceNotCanon");
        for (int prior = Math.max(1, chapter - 2); prior < chapter; prior++) {
            manuscripts.findFirstByProjectIdAndChapterNumberAndStatusOrderByVersionNumberDesc(projectId, prior,
                    ManuscriptStatus.AUTHOR_ACCEPTED).ifPresent(value -> {
                        var entry = previous.addObject();
                        entry.put("id", value.getId().toString()); entry.put("rowVersion", value.getRowVersion());
                        entry.put("chapterNumber", value.getChapterNumber()); entry.set("content", mapper.valueToTree(value.getContent()));
                    });
        }
        String prompt = names.render(projectId, data.toString());
        String fingerprint = Sha256.ofUtf8(context.boundaryFingerprint() + "\n" + context.bible().getContent()
                + "\n" + project.getCurrentCanonVersion() + "\n" + prompt);
        return new Source(new DraftLoopRun.Basis(fingerprint, prompt, ChapterWritingBasisService.capture(context)), context,
                manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapter).orElse(null));
    }

    public Source freeze(UUID projectId, int chapter, ModelProvider provider) {
        var source = capture(projectId, chapter);
        var budget = budgets.plan(AgentStage.MANUSCRIPT, provider, source.basis().prompt());
        var memory = contexts.recall(AgentStage.MANUSCRIPT, source.context(), null, budget);
        if (!source.basis().fingerprint().equals(capture(projectId, chapter).basis().fingerprint())) {
            throw new IllegalStateException("准备期间创作依据已变化，请重试");
        }
        try {
            String prompt = source.basis().prompt() + "\n【冻结的相关正史、知识边界与前文参考；注意来源标记】\n"
                    + names.render(projectId, mapper.writeValueAsString(memory));
            return new Source(new DraftLoopRun.Basis(source.basis().fingerprint(), prompt, source.basis().writingBasis()),
                    source.context(), source.latest());
        } catch (JsonProcessingException failure) {
            throw new IllegalStateException("无法冻结自动编辑资料", failure);
        }
    }
}
