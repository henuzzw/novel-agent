package com.novelagent.writing.application;

import com.novelagent.platform.support.Sha256;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.application.CreativeStrategyGuide;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.api.ManuscriptResponse;
import com.novelagent.writing.api.QualityReviewResponse;
import com.novelagent.writing.domain.ChapterContractVersion;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.domain.QualityReviewContent;
import com.novelagent.writing.domain.QualityReviewVersion;
import com.novelagent.writing.infrastructure.ChapterContractVersionRepository;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import com.novelagent.writing.infrastructure.QualityReviewVersionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 质量报告存储。
 *
 * <p>保存报告及来源指纹，复核正文、合同、风格、策略与上下文是否仍有效。修订保存为新草稿，不覆盖原文；事务锁只保护持久化阶段，不含模型等待。</p>
 */
@Service
public class QualityReviewStore {
    private final OutlineVersionRepository outlines;
    private final StoryBibleVersionRepository bibles;
    private final ChapterContractVersionRepository contracts;
    private final ManuscriptVersionRepository manuscripts;
    private final QualityReviewVersionRepository reports;
    private final ProjectAccessService access;
    private final CharacterNameService names;
    private final CharacterProfileService profiles;
    private final WritingStyleService styles;
    private final EntityManager entityManager;
    private final com.novelagent.planning.application.CreationPreparationContextService preparation;

    public QualityReviewStore(
            OutlineVersionRepository outlines,
            StoryBibleVersionRepository bibles,
            ChapterContractVersionRepository contracts,
            ManuscriptVersionRepository manuscripts,
            QualityReviewVersionRepository reports,
            ProjectAccessService access,
            CharacterNameService names,
            CharacterProfileService profiles,
            WritingStyleService styles,
            EntityManager entityManager) {
        this(outlines, bibles, contracts, manuscripts, reports, access, names, profiles, styles, entityManager, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public QualityReviewStore(
            OutlineVersionRepository outlines,
            StoryBibleVersionRepository bibles,
            ChapterContractVersionRepository contracts,
            ManuscriptVersionRepository manuscripts,
            QualityReviewVersionRepository reports,
            ProjectAccessService access,
            CharacterNameService names,
            CharacterProfileService profiles,
            WritingStyleService styles,
            EntityManager entityManager,
            com.novelagent.planning.application.CreationPreparationContextService preparation) {
        this.outlines = outlines;
        this.bibles = bibles;
        this.contracts = contracts;
        this.manuscripts = manuscripts;
        this.reports = reports;
        this.access = access;
        this.names = names;
        this.profiles = profiles;
        this.styles = styles;
        this.entityManager = entityManager;
        this.preparation = preparation;
    }

    public record Snapshot(NovelProject project, ManuscriptVersion manuscript, ChapterContractVersion contract,
            StoryBibleVersion bible, ManuscriptContent rendered, String styleContext, String profileContext, String fingerprint,
            String futureContext) {
        public Snapshot(NovelProject project, ManuscriptVersion manuscript, ChapterContractVersion contract,
                StoryBibleVersion bible, ManuscriptContent rendered, String styleContext, String profileContext, String fingerprint) {
            this(project, manuscript, contract, bible, rendered, styleContext, profileContext, fingerprint, "");
        }
    }

    /**
     * 读取当前章最新正文及相关合同、圣经、渲染内容、人物档案、风格和准备上下文，构造质量检查来源指纹。不调用模型；模型结果保存前还需重新核对来源。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapter 当前处理章号，从 1 开始。
     */
    @Transactional(readOnly = true)
    public Snapshot snapshot(UUID projectId, int chapter) {
        NovelProject project = access.requireOwnedProject(projectId);
        ManuscriptVersion manuscript = manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapter)
                .orElseThrow(() -> new IllegalArgumentException("请先保存本章正文"));
        ChapterContractVersion contract = contracts.findByIdAndProjectId(manuscript.getSourceContractVersionId(), projectId).orElseThrow();
        if (manuscript.getChapterNumber() != chapter || contract.getChapterNumber() != chapter) {
            throw new IllegalArgumentException("正文或合同不属于当前章节");
        }
        var outline = outlines.findByIdAndProjectId(contract.getSourceOutlineVersionId(), projectId)
                .filter(value -> value.getStatus() == OutlineStatus.PUBLISHED).orElseThrow();
        if (!outline.getId().equals(project.getCurrentOutlineVersionId())
                || !contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapter).orElseThrow()
                        .getId().equals(contract.getId())) throw new IllegalArgumentException("正文关联的合同或大纲已更新，请先调整正文");
        StoryBibleVersion bible = bibles.findByIdAndProjectId(outline.getSourceBibleVersionId(), projectId)
                .filter(value -> value.getStatus() == StoryBibleStatus.PUBLISHED)
                .orElseThrow(() -> new IllegalArgumentException("质量检查需要当前已发布故事圣经"));
        if (!bible.getId().equals(project.getCurrentBibleVersionId())) {
            throw new IllegalArgumentException("大纲引用的故事圣经已更新，请先调整大纲与正文");
        }
        ManuscriptContent rendered = names.render(projectId, manuscript.getContent());
        String profile = names.render(projectId, profiles.promptContext(projectId));
        String prepared = preparation == null ? "" : preparation.context(projectId, outline.getId(), chapter);
        if (!prepared.isEmpty()) profile += "\n作者确认的创作准备资料；规划不等于正史或角色已知信息：\n" + prepared;
        String style = styles.promptContext(projectId);
        return new Snapshot(project, manuscript, contract, bible, rendered, style, profile,
                fingerprint(rendered.toString() + "\n" + profile + "\n" + style + "\n" + project.getCurrentCanonVersion()
                        + "\n" + CreativeStrategyGuide.render(CreativeStrategyPolicy.from(project))
                        + "\n" + outline.getId() + ":" + outline.getRowVersion()
                        + "\n" + bible.getId() + ":" + bible.getRowVersion() + ":" + bible.getContent()
                        + "\n" + contract.getId() + ":" + contract.getRowVersion() + ":" + contract.getContent()
                        + "\n" + outline.getContent()
                        + "\n" + previousSources(projectId, chapter)),
                "未来边界，仅为当前大纲的计划，不是人物已知信息或已发生事实。来源大纲=" + outline.getId()
                        + "，行版本=" + outline.getRowVersion() + "\n"
                        + outline.getContent().arcs().stream().flatMap(arc -> arc.chapters().stream())
                                .filter(plan -> plan.number() > chapter)
                                .min(java.util.Comparator.comparingInt(com.novelagent.planning.domain.ChapterPlan::number))
                                .map(Object::toString).orElse("末章，无下一章计划。"));
    }

    /**
     * 读取最新保存结果；“最新”不自动表示已发布、已确认或已进入正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapter 当前处理章号，从 1 开始。
     * @return 匹配范围的记录；未找到时返回空 Optional。
     */
    @Transactional(readOnly = true)
    public Optional<QualityReviewResponse> latest(UUID projectId, int chapter) {
        access.requireOwnedProject(projectId);
        return reports.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapter)
                .map(report -> QualityReviewResponse.from(report, isCurrent(report)));
    }

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapter 当前处理章号，从 1 开始。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @Transactional(readOnly = true)
    public QualityReviewVersion get(UUID projectId, int chapter, UUID id) {
        access.requireOwnedProject(projectId);
        return reports.findByIdAndProjectIdAndChapterNumber(id, projectId, chapter)
                .orElseThrow(() -> new WritingResourceNotFoundException("质量报告", id));
    }

    /**
     * 重新核对报告记录的正文来源和上下文指纹，失效时阻止继续使用该报告润色。
     *
     * @param report 待保存或读取的解析、审阅报告。
     */
    @Transactional(readOnly = true)
    public void requireCurrent(QualityReviewVersion report) {
        if (!isCurrent(report)) throw new IllegalStateException("正文、规划、人物档案、创作策略或写作风格已变化，请重新检查质量");
    }

    /**
     * 在短事务内锁定并刷新相关记录，复核检查来源指纹后保存不可变质量报告；来源已变时拒绝保存旧报告。这里只记录质量意见，不接受正文或提交正史。
     *
     * @param source 生成或检查前读取的来源快照，用于保存时再次复核。
     * @param provider 实际生成器来源标识，随报告保存以便追溯。
     * @param instruction 作者本次要求，只能在已有事实与授权边界内执行。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     */
    @Transactional
    public QualityReviewResponse save(Snapshot source, String provider, String instruction, QualityReviewContent content) {
        Snapshot current = lockedSnapshot(source.project().getId(), source.manuscript().getChapterNumber());
        if (!sameSource(source, current)) throw new IllegalStateException("检查期间正文或写作依据已变化，请重新检查");
        int version = reports.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(current.project().getId(),
                current.manuscript().getChapterNumber()).map(report -> report.getVersionNumber() + 1).orElse(1);
        QualityReviewVersion report = QualityReviewVersion.create(current.project().getId(), current.manuscript().getChapterNumber(),
                version, current.manuscript().getId(), current.manuscript().getRowVersion(), current.project().getCurrentOutlineVersionId(),
                current.fingerprint(), provider, instruction, content);
        return QualityReviewResponse.from(reports.saveAndFlush(report), true);
    }

    /**
     * 锁内复核报告与最新正文来源，保存模型生成的新修订草稿并保留基准稿关联；不覆盖原文，不自动接受新稿。
     *
     * @param report 待保存或读取的解析、审阅报告。
     * @param draft 尚未发布或确认的草稿。
     */
    @Transactional
    public ManuscriptResponse saveRevision(QualityReviewVersion report, ManuscriptVersion draft) {
        Snapshot current = lockedSnapshot(report.getProjectId(), report.getChapterNumber());
        if (!matches(report, current) || !draft.getSourceContractVersionId().equals(current.contract().getId())
                || !report.getSourceManuscriptId().equals(draft.getBaseManuscriptVersionId())) {
            throw new IllegalStateException("润色期间正文或写作依据已变化，请重新检查");
        }
        ManuscriptVersion saved = manuscripts.saveAndFlush(draft);
        return ManuscriptResponse.from(saved, names.render(report.getProjectId(), saved.getContent()));
    }

    /**
     * 在保存阶段锁定所需来源后重建检查快照，模型等待发生在本方法事务之外。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapter 当前处理章号，从 1 开始。
     */
    private Snapshot lockedSnapshot(UUID projectId, int chapter) {
        NovelProject project = access.requireOwnedProject(projectId);
        entityManager.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        Snapshot current = snapshot(projectId, chapter);
        entityManager.refresh(current.manuscript(), LockModeType.PESSIMISTIC_WRITE);
        return snapshot(projectId, chapter);
    }

    private boolean isCurrent(QualityReviewVersion report) {
        try { return matches(report, snapshot(report.getProjectId(), report.getChapterNumber())); }
        catch (IllegalArgumentException exception) { return false; }
    }

    private static boolean matches(QualityReviewVersion report, Snapshot current) {
        return report.getSourceManuscriptId().equals(current.manuscript().getId())
                && report.getSourceManuscriptRowVersion() == current.manuscript().getRowVersion()
                && report.getSourceOutlineId().equals(current.project().getCurrentOutlineVersionId())
                && report.getSourceTextHash().equals(current.fingerprint());
    }

    /**
     * 比较质量报告与最新源正文关联及行版本，源变更后旧报告不能继续用于修订。
     *
     * @param expected 调用方持有的预期编辑行版本，不是章节生成序号。
     * @param current 操作开始时读取的当前记录或版本。
     */
    private static boolean sameSource(Snapshot expected, Snapshot current) {
        return expected.manuscript().getId().equals(current.manuscript().getId())
                && expected.manuscript().getRowVersion() == current.manuscript().getRowVersion()
                && expected.project().getCurrentOutlineVersionId().equals(current.project().getCurrentOutlineVersionId())
                && expected.fingerprint().equals(current.fingerprint());
    }


    private String previousSources(UUID projectId, int chapter) {
        StringBuilder source = new StringBuilder();
        for (int prior = Math.max(1, chapter - 2); prior < chapter; prior++) {
            source.append("chapter=").append(prior).append('\n');
            manuscripts.findFirstByProjectIdAndChapterNumberAndStatusOrderByVersionNumberDesc(
                    projectId, prior, ManuscriptStatus.AUTHOR_ACCEPTED).ifPresent(value -> source.append("accepted=")
                            .append(value.getId()).append(':').append(value.getRowVersion()).append(':').append(value.getContent()).append('\n'));
            contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, prior)
                    .ifPresent(value -> source.append("contract=").append(value.getId()).append(':')
                            .append(value.getRowVersion()).append(':').append(value.getContent()).append('\n'));
        }
        return source.toString();
    }

    /**
     * 将本次正文及检查依据计算为稳定指纹，绑定报告适用范围；不是正文无错误的证明。
     *
     * @param value 当前业务对象或作者编辑值，具体类型由方法签名确定。
     */
    private static String fingerprint(String value) {
        return Sha256.ofUtf8(value);
    }
}
