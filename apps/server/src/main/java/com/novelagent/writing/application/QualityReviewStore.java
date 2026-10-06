package com.novelagent.writing.application;

import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.project.application.CreativeStrategyGuide;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.project.infrastructure.NovelProjectRepository;
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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class QualityReviewStore {
    private final NovelProjectRepository projects;
    private final OutlineVersionRepository outlines;
    private final StoryBibleVersionRepository bibles;
    private final ChapterContractVersionRepository contracts;
    private final ManuscriptVersionRepository manuscripts;
    private final QualityReviewVersionRepository reports;
    private final CurrentActorProvider actors;
    private final CharacterNameService names;
    private final CharacterProfileService profiles;
    private final WritingStyleService styles;
    private final EntityManager entityManager;
    private final com.novelagent.planning.application.CreationPreparationContextService preparation;

    public QualityReviewStore(NovelProjectRepository projects, OutlineVersionRepository outlines,
            StoryBibleVersionRepository bibles, ChapterContractVersionRepository contracts,
            ManuscriptVersionRepository manuscripts, QualityReviewVersionRepository reports, CurrentActorProvider actors,
            CharacterNameService names, CharacterProfileService profiles, WritingStyleService styles, EntityManager entityManager) {
        this(projects, outlines, bibles, contracts, manuscripts, reports, actors, names, profiles, styles, entityManager, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public QualityReviewStore(NovelProjectRepository projects, OutlineVersionRepository outlines,
            StoryBibleVersionRepository bibles, ChapterContractVersionRepository contracts,
            ManuscriptVersionRepository manuscripts, QualityReviewVersionRepository reports, CurrentActorProvider actors,
            CharacterNameService names, CharacterProfileService profiles, WritingStyleService styles, EntityManager entityManager,
            com.novelagent.planning.application.CreationPreparationContextService preparation) {
        this.projects = projects;
        this.outlines = outlines;
        this.bibles = bibles;
        this.contracts = contracts;
        this.manuscripts = manuscripts;
        this.reports = reports;
        this.actors = actors;
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

    @Transactional(readOnly = true)
    public Snapshot snapshot(UUID projectId, int chapter) {
        NovelProject project = owned(projectId);
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

    @Transactional(readOnly = true)
    public Optional<QualityReviewResponse> latest(UUID projectId, int chapter) {
        owned(projectId);
        return reports.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapter)
                .map(report -> QualityReviewResponse.from(report, isCurrent(report)));
    }

    @Transactional(readOnly = true)
    public QualityReviewVersion get(UUID projectId, int chapter, UUID id) {
        owned(projectId);
        return reports.findByIdAndProjectIdAndChapterNumber(id, projectId, chapter)
                .orElseThrow(() -> new WritingResourceNotFoundException("质量报告", id));
    }

    @Transactional(readOnly = true)
    public void requireCurrent(QualityReviewVersion report) {
        if (!isCurrent(report)) throw new IllegalStateException("正文、规划、人物档案、创作策略或写作风格已变化，请重新检查质量");
    }

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

    private Snapshot lockedSnapshot(UUID projectId, int chapter) {
        NovelProject project = owned(projectId);
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

    private static boolean sameSource(Snapshot expected, Snapshot current) {
        return expected.manuscript().getId().equals(current.manuscript().getId())
                && expected.manuscript().getRowVersion() == current.manuscript().getRowVersion()
                && expected.project().getCurrentOutlineVersionId().equals(current.project().getCurrentOutlineVersionId())
                && expected.fingerprint().equals(current.fingerprint());
    }

    private NovelProject owned(UUID projectId) {
        return projects.findById(projectId).filter(project -> project.getOwnerId().equals(actors.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
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

    private static String fingerprint(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 不可用", exception); }
    }
}
