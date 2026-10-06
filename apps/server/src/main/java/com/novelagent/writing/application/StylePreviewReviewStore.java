package com.novelagent.writing.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import com.novelagent.writing.domain.QualityReviewContent;
import com.novelagent.writing.domain.StylePreviewReview;
import com.novelagent.writing.domain.StylePreviewSource;
import com.novelagent.writing.infrastructure.StylePreviewReviewRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 试写报告存储。
 *
 * <p>保存不可变源样例、报告与依据指纹，并锁内认领有限修订尝试。旧大纲、风格或来源变化使报告不可复用，失败也占用尝试，避免隐式重复计费。</p>
 */
@Service
public class StylePreviewReviewStore {
    private final WritingContextService contexts;
    private final StylePreviewReviewRepository reports;
    private final CharacterNameService names;
    private final CharacterProfileService profiles;
    private final ObjectMapper mapper;
    private final EntityManager entities;

    public StylePreviewReviewStore(WritingContextService contexts, StylePreviewReviewRepository reports,
            CharacterNameService names, CharacterProfileService profiles, ObjectMapper mapper, EntityManager entities) {
        this.contexts = contexts;
        this.reports = reports;
        this.names = names;
        this.profiles = profiles;
        this.mapper = mapper;
        this.entities = entities;
    }

    public record Snapshot(WritingContextService.Context context, StylePreviewSource source, String hash) { }

    /**
     * 读取指定开头样例及关联大纲、圣经、人物命名、档案和候选风格，生成独立试写审阅依据；不使用项目已应用风格替换候选风格。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param source 本次试写的原样例、候选风格及大纲来源版本。
     */
    @Transactional(readOnly = true)
    public Snapshot snapshot(UUID projectId, StylePreviewSource source) {
        var context = contexts.previewContext(projectId, source.outlineVersionId());
        WritingChecks.check(context.outline().getRowVersion(), source.expectedOutlineVersion());
        return capture(projectId, context, source);
    }

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @Transactional(readOnly = true)
    public StylePreviewReview get(UUID projectId, UUID id) {
        contexts.requireOwnedProject(projectId);
        return requireReport(projectId, id);
    }

    /**
     * 在短事务中重读并复核试写来源后保存报告，保留样例与指纹；报告不是正式正文版本，也不触发正史审批。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param source 生成或检查前读取的来源快照，用于保存时再次复核。
     * @param content 待保存或生成的内容，仍须满足来源与状态约束。
     */
    @Transactional
    public StylePreviewReview save(UUID projectId, Snapshot source, QualityReviewContent content) {
        requireUnchanged(source, lockedSnapshot(projectId, source.source()));
        return reports.saveAndFlush(StylePreviewReview.create(projectId, source.hash(), source.source(), content));
    }

    /**
     * 锁内认领选定报告的修订操作并校验来源，避免同一报告被并发重复使用；认领后模型在事务之外执行。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expectedHash 认领前预期依据哈希，必须仍与当前来源一致。
     */
    @Transactional
    public StylePreviewReview claim(UUID projectId, UUID id, String expectedHash) {
        contexts.requireOwnedProject(projectId);
        var report = requireReport(projectId, id);
        entities.refresh(report, LockModeType.PESSIMISTIC_WRITE);
        var current = lockedSnapshot(projectId, report.getSource());
        if (!report.getSourceHash().equals(expectedHash) || !expectedHash.equals(current.hash())) {
            throw new IllegalStateException("试写依据已变化，请重新检查");
        }
        report.beginRevision();
        return reports.saveAndFlush(report);
    }

    /**
     * 复核报告或快照仍对应当前试写依据，来源改变时拒绝复用检查或迟到修订。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param source 生成或检查前读取的来源快照，用于保存时再次复核。
     */
    @Transactional(readOnly = true)
    public void requireCurrent(UUID projectId, Snapshot source) {
        requireUnchanged(source, snapshot(projectId, source.source()));
    }

    /**
     * 锁定试写报告所依赖的规划来源后重建快照，防止认领或保存阶段使用过期依据。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param source 本次试写的原样例、候选风格及大纲来源版本。
     */
    private Snapshot lockedSnapshot(UUID projectId, StylePreviewSource source) {
        var project = contexts.requireOwnedProject(projectId);
        entities.refresh(project, LockModeType.PESSIMISTIC_READ);
        var context = contexts.previewContext(projectId, source.outlineVersionId());
        entities.refresh(context.outline(), LockModeType.PESSIMISTIC_READ);
        entities.refresh(context.bible(), LockModeType.PESSIMISTIC_READ);
        WritingChecks.check(context.outline().getRowVersion(), source.expectedOutlineVersion());
        return capture(projectId, context, source);
    }

    private Snapshot capture(UUID projectId, WritingContextService.Context context, StylePreviewSource source) {
        try {
            String json = mapper.writeValueAsString(List.of(source, context.bible().getId(),
                    context.bible().getRowVersion(), context.bible().getContent(), context.arc(), context.chapter(),
                    profiles.promptContext(projectId), WritingStyleGuide.render(source.profile()),
                    CreativeStrategyPolicy.from(contexts.requireOwnedProject(projectId))));
            String rendered = names.render(projectId, json);
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(rendered.getBytes(StandardCharsets.UTF_8)));
            return new Snapshot(context, source, hash);
        } catch (JsonProcessingException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("不能校验试写检查依据", exception);
        }
    }

    private StylePreviewReview requireReport(UUID projectId, UUID id) {
        return reports.findByIdAndProjectId(id, projectId)
                .orElseThrow(() -> new WritingResourceNotFoundException("试写检查", id));
    }

    /**
     * 比较当前快照与原报告来源指纹，任何重要依据变化都要求重新检查而非继续复用旧建议。
     *
     * @param previous 前一阶段、前置依赖或原版本快照。
     * @param current 操作开始时读取的当前记录或版本。
     */
    private static void requireUnchanged(Snapshot previous, Snapshot current) {
        if (!previous.hash().equals(current.hash())) throw new IllegalStateException("检查期间试写依据已变化，请重新检查");
    }
}
