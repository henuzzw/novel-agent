package com.novelagent.writing.infrastructure;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.writing.application.FirstThreeChaptersStore;
import com.novelagent.writing.application.QualityReviewStore;
import com.novelagent.writing.application.WritingResourceNotFoundException;
import com.novelagent.writing.application.WritingStyleService;
import com.novelagent.writing.api.QualityReviewResponse;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.FirstThreeChaptersBudget;
import com.novelagent.writing.domain.FirstThreeChaptersContent;
import com.novelagent.writing.domain.FirstThreeChaptersReport;
import com.novelagent.writing.domain.FirstThreeChaptersSource;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * 三章报告存储。
 *
 * <p>读取完整三章正文及规划、风格、档案等依赖，构造来源指纹并保存私有报告。保存阶段重新核对来源，不以摘要代替完整正文。</p>
 */
@Component
public class FirstThreeChaptersPersistence implements FirstThreeChaptersStore {
    private final ProjectAccessService access;
    private final CurrentActorProvider actors;
    private final OutlineVersionRepository outlines;
    private final StoryBibleVersionRepository bibles;
    private final ManuscriptVersionRepository manuscripts;
    private final ChapterContractVersionRepository contracts;
    private final FirstThreeChaptersReportRepository reports;
    private final CharacterNameService names;
    private final CharacterProfileService profiles;
    private final WritingStyleService styles;
    private final QualityReviewStore quality;
    private final EntityManager em;
    private final ObjectMapper mapper;
    private final JdbcTemplate jdbc;
    private final QualityReviewVersionRepository qualityReports;

    public FirstThreeChaptersPersistence(ProjectAccessService access, CurrentActorProvider actors,
            OutlineVersionRepository outlines, StoryBibleVersionRepository bibles,
            ManuscriptVersionRepository manuscripts, ChapterContractVersionRepository contracts,
            FirstThreeChaptersReportRepository reports, CharacterNameService names, CharacterProfileService profiles,
            WritingStyleService styles, QualityReviewStore quality, EntityManager em, ObjectMapper mapper,
            JdbcTemplate jdbc, QualityReviewVersionRepository qualityReports) {
        this.access = access; this.actors = actors; this.outlines = outlines; this.bibles = bibles;
        this.manuscripts = manuscripts; this.contracts = contracts; this.reports = reports;
        this.names = names; this.profiles = profiles; this.styles = styles; this.quality = quality;
        this.em = em; this.mapper = mapper;
        this.jdbc = jdbc; this.qualityReports = qualityReports;
    }

    /**
     * 读取作者明确选择或当前可用的前三章完整正文及依赖，计算一致来源快照；缺失章节不以摘要代替。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param selection 作者显式选定的正文版本 ID 列表，顺序和范围须满足三章检查要求。
     */
    @Override @Transactional(readOnly = true)
    public FirstThreeChaptersSource snapshot(UUID projectId, List<UUID> selection) {
        return read(projectId, selection, false);
    }

    /**
     * 读取完整源章及依赖配置，保持实际正文和状态边界；缺章不从合同或摘要补正文。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param selection 作者显式选定的正文版本 ID 列表，顺序和范围须满足三章检查要求。
     * @param lock 是否加数据库行锁；加锁需要调用方维持有效事务。
     */
    private FirstThreeChaptersSource read(UUID projectId, List<UUID> selection, boolean lock) {
        var project = access.requireOwnedProject(projectId);
        if (lock) em.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        var reasons = new ArrayList<String>();
        var outline = project.getCurrentOutlineVersionId() == null ? null
                : outlines.findByIdAndProjectId(project.getCurrentOutlineVersionId(), projectId).orElse(null);
        if (outline != null && lock) em.refresh(outline, LockModeType.PESSIMISTIC_READ);
        if (outline == null || outline.getStatus() != OutlineStatus.PUBLISHED) reasons.add("请先发布当前大纲");
        var bible = outline == null ? null : bibles.findByIdAndProjectId(outline.getSourceBibleVersionId(), projectId).orElse(null);
        if (bible != null && lock) em.refresh(bible, LockModeType.PESSIMISTIC_READ);
        if (bible == null || bible.getStatus() != StoryBibleStatus.PUBLISHED
                || !bible.getId().equals(project.getCurrentBibleVersionId())) reasons.add("大纲关联圣经不是当前已发布版本，来源过期");
        var chapters = new ArrayList<FirstThreeChaptersSource.Chapter>();
        var dependencies = new ArrayList<Object>();
        dependencies.add(project.getRowVersion());
        dependencies.add(project.getCurrentBibleVersionId());
        dependencies.add(jdbc.queryForList("SELECT character_id, row_version FROM character_profile WHERE project_id = ? ORDER BY character_id"
                + (lock ? " FOR SHARE" : ""), projectId));
        for (int number = 1; number <= 3; number++) {
            final int chapterNumber = number;
            if (outline != null && outline.getContent().arcs().stream().flatMap(a -> a.chapters().stream())
                    .noneMatch(c -> c.number() == chapterNumber)) reasons.add("当前大纲缺少第" + number + "章");
            var versions = manuscripts.findAllByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, number);
            var latest = versions.isEmpty() ? null : versions.getFirst();
            if (latest != null && lock) em.refresh(latest, LockModeType.PESSIMISTIC_READ);
            var manuscript = selection.isEmpty() ? latest : manuscripts
                    .findByIdAndProjectIdAndChapterNumber(selection.get(number - 1), projectId, number)
                    .orElseThrow(() -> new WritingResourceNotFoundException("正文", selection.get(chapterNumber - 1)));
            if (manuscript != null && lock) em.refresh(manuscript, LockModeType.PESSIMISTIC_READ);
            var contract = manuscript == null ? null : contracts.findByIdAndProjectIdAndChapterNumber(
                    manuscript.getSourceContractVersionId(), projectId, number).orElse(null);
            if (contract != null && lock) em.refresh(contract, LockModeType.PESSIMISTIC_READ);
            var latestContract = contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, number).orElse(null);
            if (latestContract != null && lock) em.refresh(latestContract, LockModeType.PESSIMISTIC_READ);
            // Track the heads even for an explicitly selected historical manuscript.
            dependencies.add(latest == null ? "missing manuscript" : List.of(latest.getId(), latest.getRowVersion(), latest.getContent()));
            dependencies.add(latestContract == null ? "missing contract" : List.of(latestContract.getId(), latestContract.getRowVersion(), latestContract.getContent()));
            if (manuscript == null || manuscript.getContent().body().isBlank()) reasons.add("缺少第" + number + "章完整正文");
            if (contract == null) reasons.add("第" + number + "章没有对应合同");
            else if (outline == null || !contract.getSourceOutlineVersionId().equals(outline.getId())
                    || latestContract == null || !latestContract.getId().equals(contract.getId()))
                reasons.add("第" + number + "章合同或大纲已更新，来源过期");
            var text = manuscript == null ? null : names.render(projectId, manuscript.getContent());
            var review = qualityReports.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, number)
                    .map(r -> QualityReviewResponse.from(r, false)).orElse(null);
            if (review != null && manuscript != null && contract != null && outline != null && latestContract != null
                    && outline.getStatus() == OutlineStatus.PUBLISHED && latestContract.getId().equals(contract.getId())
                    && contract.getSourceOutlineVersionId().equals(outline.getId())) {
                review = quality.latest(projectId, number).orElse(review);
            }
            boolean qualityCurrent = review != null && review.current() && manuscript != null
                    && review.sourceManuscriptId().equals(manuscript.getId())
                    && review.sourceManuscriptRowVersion() == manuscript.getRowVersion();
            var chapter = new FirstThreeChaptersSource.Chapter(number, manuscript == null ? null : manuscript.getId(),
                    manuscript == null ? 0 : manuscript.getVersionNumber(), manuscript == null ? 0 : manuscript.getRowVersion(),
                    manuscript == null ? "MISSING" : manuscript.getStatus().name(), text == null ? "" : text.title(), text == null ? null : text.body(),
                    contract == null ? null : contract.getId(), contract == null ? 0 : contract.getVersionNumber(),
                    contract == null ? 0 : contract.getRowVersion(), contract == null ? "MISSING" : contract.getStatus().name(),
                    contract == null ? null : names.render(projectId, contract.getContent(), ChapterContractContent.class),
                    versions.stream().map(v -> new FirstThreeChaptersSource.Version(v.getId(), v.getVersionNumber(), v.getRowVersion(), v.getStatus().name())).toList(),
                    review == null ? null : review.content(), qualityCurrent);
            chapters.add(chapter);
            dependencies.add(Arrays.asList(chapter.manuscriptId(), chapter.rowVersion(), chapter.body(), chapter.title(),
                    chapter.status(), chapter.contractId(), chapter.contractRowVersion(), chapter.contractStatus(), chapter.contract()));
        }
        String outlineText = outline == null ? "" : json(names.render(projectId, outline.getContent(), OutlineContent.class));
        String bibleText = bible == null ? "" : json(names.render(projectId, bible.getContent(), StoryBibleContent.class));
        String style = styles.promptContext(projectId);
        String profile = names.render(projectId, profiles.promptContext(projectId));
        String strategy = json(CreativeStrategyPolicy.from(project));
        dependencies.add(Arrays.asList(project.getCurrentOutlineVersionId(), outline == null ? 0 : outline.getRowVersion(),
                bible == null ? null : bible.getId(), bible == null ? 0 : bible.getRowVersion(), project.getCurrentCanonVersion(),
                outlineText, bibleText, style, profile, strategy, project.getSetting("writingStyle")));
        return new FirstThreeChaptersSource(projectId, project.getCurrentOutlineVersionId(), outline == null ? 0 : outline.getRowVersion(),
                bible == null ? null : bible.getId(), bible == null ? 0 : bible.getRowVersion(), project.getCurrentCanonVersion(),
                strategy, outlineText, bibleText, style, profile, List.copyOf(chapters), List.copyOf(reasons), hash(json(dependencies)));
    }

    /**
     * 返回当前作者在本项目的最新连读报告，以及同指纹的最近报告（若不同则补充），避免重复返回同一记录；查询不重新执行检查。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param fingerprint 报告对应的精确来源指纹。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Override @Transactional(readOnly = true)
    public List<FirstThreeChaptersReport> latest(UUID projectId, String fingerprint) {
        access.requireOwnedProject(projectId);
        var values = new ArrayList<FirstThreeChaptersReport>();
        reports.findFirstByProjectIdAndAuthorIdOrderByVersionNumberDesc(projectId, actors.currentUserId()).ifPresent(values::add);
        reports.findFirstByProjectIdAndAuthorIdAndFingerprintOrderByVersionNumberDesc(projectId, actors.currentUserId(), fingerprint)
                .filter(r -> values.stream().noneMatch(v -> v.getId().equals(r.getId()))).ifPresent(values::add);
        return List.copyOf(values);
    }

    /**
     * 在短事务中重新读取来源并比较指纹，来源不变才保存三章报告与预算信息；不接受正文、不修改正史。
     *
     * @param source 生成或检查前读取的来源快照，用于保存时再次复核。
     * @param selection 作者显式选定的正文版本 ID 列表，顺序和范围须满足三章检查要求。
     * @param provider 实际生成器来源标识，随报告保存以便追溯。
     * @param instruction 作者本次要求，只能在已有事实与授权边界内执行。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     * @param budget 本次上下文或检查预算，约束输入范围但不是实际费用。
     */
    @Override @Transactional
    public FirstThreeChaptersReport save(FirstThreeChaptersSource source, List<UUID> selection, String provider,
            String instruction, FirstThreeChaptersContent content, FirstThreeChaptersBudget budget) {
        // Generation has finished; refresh dependencies under short-lived locks before inserting a report.
        em.clear();
        var current = read(source.projectId(), selection, true);
        if (!current.available() || !source.fingerprint().equals(current.fingerprint()))
            throw new IllegalStateException("检查期间正文、合同或写作依据已变化，请重新检查");
        int version = reports.findFirstByProjectIdAndAuthorIdOrderByVersionNumberDesc(source.projectId(), actors.currentUserId())
                .map(r -> r.getVersionNumber() + 1).orElse(1);
        return reports.saveAndFlush(FirstThreeChaptersReport.create(actors.currentUserId(), version, provider, instruction, source, content, budget));
    }

    private String json(Object value) {
        try { return mapper.writer().with(com.fasterxml.jackson.databind.SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value); }
        catch (JsonProcessingException e) { throw new IllegalStateException("无法序列化通读依据", e); }
    }
    private static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
}
