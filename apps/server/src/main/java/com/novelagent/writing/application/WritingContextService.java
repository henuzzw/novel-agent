package com.novelagent.writing.application;

import com.novelagent.agent.application.AgentStage;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.PublishedMemoryReadiness;
import com.novelagent.memory.application.MemoryBudgetAllocator;
import com.novelagent.memory.application.MemoryBudgetPlan;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.memory.application.NovelMemoryService;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.CharacterBlueprint;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.CreativeBoundaryGuide;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.CreativeBoundaries;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.domain.NovelProject;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 写作依据上下文。
 *
 * <p>在短只读事务中组装当前发布规划、记忆预算和适用创作准备。正式写作与允许草稿大纲的试写分开；回忆正文、有效事实和未来计划保持标记。</p>
 */
@Service
public class WritingContextService {
    private final ProjectAccessService access;
    private final OutlineVersionRepository outlines;
    private final StoryBibleVersionRepository bibles;
    private final NovelMemoryService memory;
    private final CharacterNameService names;
    private final PublishedMemoryReadiness memoryReadiness;

    public WritingContextService(
            ProjectAccessService access,
            OutlineVersionRepository outlines,
            StoryBibleVersionRepository bibles,
            NovelMemoryService memory,
            CharacterNameService names,
            PublishedMemoryReadiness memoryReadiness) {
        this.access = access;
        this.outlines = outlines;
        this.bibles = bibles;
        this.memory = memory;
        this.names = names;
        this.memoryReadiness = memoryReadiness;
    }

    /**
     * 组装正式章节写作依据：当前发布规划资料、大纲、章节及适用创作准备，保留规划与正史边界。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     */
    @Transactional(readOnly = true)
    public Context context(UUID projectId, int chapterNumber) {
        NovelProject project = requireOwnedProject(projectId);
        UUID outlineId = project.getCurrentOutlineVersionId();
        if (outlineId == null) {
            throw new IllegalArgumentException("请先发布分层大纲");
        }
        OutlineVersion outline =
                outlines.findByIdAndProjectId(outlineId, projectId)
                        .filter(value -> value.getStatus() == OutlineStatus.PUBLISHED)
                        .orElseThrow(() -> new IllegalArgumentException("项目当前大纲不可用"));
        StoryBibleVersion bible =
                bibles.findByIdAndProjectId(outline.getSourceBibleVersionId(), projectId)
                        .orElseThrow(() -> new IllegalArgumentException("大纲关联的已确认雪花资料不可用"));
        if (bible.getStatus() != StoryBibleStatus.PUBLISHED
                || !outline.getSourceBibleVersionId().equals(project.getCurrentBibleVersionId())) {
            throw new IllegalArgumentException("大纲关联的已确认雪花资料必须已发布且与项目当前规划资料一致");
        }
        return prepared(
                resolve(outline, bible, chapterNumber, CreativeStrategyPolicy.from(project)),
                project);
    }

    /**
     * 确认项目属于当前用户，否则以项目不可见处理；本方法不锁定项目或保证后续生成期间来源不变。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    public NovelProject requireOwnedProject(UUID projectId) {
        return access.requireOwnedProject(projectId);
    }

    /**
     * 校验项目归属及前文发布记忆状态，阻止记忆未完成或早期修订依赖未处理时生成新章。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param chapter 目标章节号，从 1 开始
     */
    public void requireMemoryReady(UUID projectId, int chapter) {
        access.requireOwnedProject(projectId);
        memoryReadiness.requireReady(projectId, chapter);
    }

    /**
     * 读取指定已保存大纲的第一章试写依据，可使用草稿大纲；不因此放行正式正文创作。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param outlineVersionId 作者显式指定的大纲版本 ID。
     */
    @Transactional(readOnly = true)
    public Context previewContext(UUID projectId, UUID outlineVersionId) {
        NovelProject project = requireOwnedProject(projectId);
        OutlineVersion outline =
                outlines.findByIdAndProjectId(outlineVersionId, projectId)
                        .orElseThrow(
                                () -> new WritingResourceNotFoundException("大纲", outlineVersionId));
        StoryBibleVersion bible =
                bibles.findByIdAndProjectId(outline.getSourceBibleVersionId(), projectId)
                        .orElseThrow(
                                () ->
                                        new WritingResourceNotFoundException(
                                                "已确认雪花资料", outline.getSourceBibleVersionId()));
        return prepared(resolve(outline, bible, 1, CreativeStrategyPolicy.from(project)), project);
    }

    /** 正文生成与后续检查共用项目创作边界，保证同一来源的指纹一致。 */
    static Context prepared(Context context, NovelProject project) {
        String boundaries = CreativeBoundaryGuide.render(CreativeBoundaries.from(project));
        return new Context(
                context.outline(),
                context.bible(),
                context.arc(),
                context.chapter(),
                context.previous(),
                context.next(),
                context.creativeStrategy(),
                boundaries);
    }

    /**
     * 按阶段、项目、章节与预算召回只读记忆，分别标记有效事实、前文参考和未来计划。
     *
     * @param stage 本次记忆或生成所处阶段，用于选择工具与预算。
     * @param context 当前章节与关联依据的上下文，未来计划不是正史。
     * @param instruction 作者本次要求，只能在已有事实与授权边界内执行。
     * @param budget 本次上下文或检查预算，约束输入范围但不是实际费用。
     */
    public NovelMemoryContext recall(
            AgentStage stage, Context context, String instruction, MemoryBudgetPlan budget) {
        NovelProject project = requireOwnedProject(context.outline().getProjectId());
        String query =
                names.render(
                        project.getId(),
                        context.chapter().title()
                                + " "
                                + context.chapter().content()
                                + " "
                                + (instruction == null ? "" : instruction));
        NovelMemoryContext recalled =
                memory.recall(
                        stage,
                        project.getId(),
                        context.chapter().number(),
                        project.getCurrentCanonVersion(),
                        query,
                        budget,
                        context.bible().getId(),
                        context.bible().getContent() == null
                                ? List.of()
                                : context.bible().getContent().characterBlueprints().stream()
                                        .map(CharacterBlueprint::name)
                                        .filter(query::contains)
                                        .toList());
        if (context.previous() == null
                && context.next() == null
                && recalled.futureContext().isEmpty()) return recalled;
        var future =
                new NovelMemoryContext.SemanticMemory(
                        context.next() == null
                                ? context.chapter().number()
                                : context.next().chapter().number(),
                        NovelMemoryContext.FUTURE_PLAN,
                        1.0,
                        context.next() == null
                                ? "末章，无下一章计划。"
                                : "下一章第" + context.next().chapter().number() + "章，仅为未来计划。",
                        context.futureContext());
        return MemoryBudgetAllocator.withFutureContext(recalled, future, budget);
    }

    static Context resolve(
            OutlineVersion outline,
            StoryBibleVersion bible,
            int number,
            CreativeStrategyPolicy policy) {
        List<ChapterBoundary> chapters =
                outline.getContent().arcs().stream()
                        .flatMap(
                                arc ->
                                        arc.chapters().stream()
                                                .map(chapter -> new ChapterBoundary(arc, chapter)))
                        .sorted(Comparator.comparingInt(value -> value.chapter().number()))
                        .toList();
        for (int i = 0; i < chapters.size(); i++) {
            ChapterBoundary current = chapters.get(i);
            if (current.chapter().number() == number) {
                if (current.chapter().contentNeedsUpdate()) {
                    throw new IllegalArgumentException("本章规划依据已变化，请先更新章节规划文字块");
                }
                return new Context(
                        outline,
                        bible,
                        current.arc(),
                        current.chapter(),
                        i == 0 ? null : chapters.get(i - 1),
                        i + 1 == chapters.size() ? null : chapters.get(i + 1),
                        policy);
            }
        }
        throw new IllegalArgumentException("当前大纲中不存在第 " + number + " 章");
    }

    /**
     * 根据当前上下文与召回来源计算边界指纹，用于识别记忆或依据变化，不替代文学正确性检查。
     *
     * @param context 当前章节与关联依据的上下文，未来计划不是正史。
     * @param recalled 按记忆策略召回的历史信息，不是新增正史事实。
     */
    public static String boundaryFingerprint(Context context, NovelMemoryContext recalled) {
        return NovelMemoryContext.fingerprint(
                context.boundaryFingerprint() + "\n" + recalled.boundaryFingerprint());
    }

    public record ChapterBoundary(OutlineArc arc, ChapterPlan chapter) {}

    public record Context(
            OutlineVersion outline,
            StoryBibleVersion bible,
            OutlineArc arc,
            ChapterPlan chapter,
            ChapterBoundary previous,
            ChapterBoundary next,
            CreativeStrategyPolicy creativeStrategy,
            String preparationContext) {
        public Context {
            preparationContext = preparationContext == null ? "" : preparationContext;
        }

        public Context(
                OutlineVersion outline,
                StoryBibleVersion bible,
                OutlineArc arc,
                ChapterPlan chapter,
                ChapterBoundary previous,
                ChapterBoundary next,
                CreativeStrategyPolicy creativeStrategy) {
            this(outline, bible, arc, chapter, previous, next, creativeStrategy, "");
        }

        public Context(
                OutlineVersion outline,
                StoryBibleVersion bible,
                OutlineArc arc,
                ChapterPlan chapter) {
            this(
                    outline,
                    bible,
                    arc,
                    chapter,
                    null,
                    null,
                    CreativeStrategyPolicy.of(CreativeStrategy.STANDARD));
        }

        public String futureContext() {
            return "【未来规划边界：不属于已发生事实或人物已知信息】\n"
                    + "来源项目="
                    + outline.getProjectId()
                    + "；大纲="
                    + outline.getId()
                    + "；大纲行版本="
                    + outline.getRowVersion()
                    + "；规划资料="
                    + outline.getSourceBibleVersionId()
                    + "；规划资料行版本="
                    + bible.getRowVersion()
                    + "\n"
                    + (next == null
                            ? "当前为末章，无下一章计划。"
                            : "下一章所在卷/幕："
                                    + next.arc().ordinal()
                                    + " "
                                    + next.arc().title()
                                    + "\n下一章计划："
                                    + next.chapter());
        }

        public String instructionWithPreparation(String instruction) {
            if (preparationContext.isEmpty()) return instruction;
            return (instruction == null ? "" : instruction)
                    + "\n【项目创作边界与补充资料；设置不授权改写正史或已确认计划】\n"
                    + preparationContext;
        }

        public Object[] budgetInputs(Object... inputs) {
            if (preparationContext.isEmpty()) return inputs;
            Object[] result = Arrays.copyOf(inputs, inputs.length + 1);
            result[inputs.length] = preparationContext;
            return result;
        }

        public StoryBibleContent writingMaterials() {
            return WritingMaterialSelection.forChapter(bible.getContent(), chapter.content());
        }

        public OutlineArc writingArc() {
            return WritingMaterialSelection.forChapter(arc, chapter);
        }

        public String boundaryFingerprint() {
            return NovelMemoryContext.fingerprint(
                    outline.getProjectId()
                            + "\n"
                            + outline.getId()
                            + "\n"
                            + outline.getRowVersion()
                            + "\n"
                            + outline.getStatus()
                            + "\n"
                            + bible.getId()
                            + "\n"
                            + bible.getRowVersion()
                            + "\n"
                            + bible.getStatus()
                            + "\n"
                            + previous
                            + "\n"
                            + arc
                            + "\n"
                            + chapter
                            + "\n"
                            + next
                            + "\n"
                            + creativeStrategy
                            + "\n"
                            + preparationContext);
        }
    }
}
