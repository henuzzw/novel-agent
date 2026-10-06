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

    @Transactional(readOnly = true)
    public Snapshot snapshot(UUID projectId, StylePreviewSource source) {
        var context = contexts.previewContext(projectId, source.outlineVersionId());
        WritingChecks.check(context.outline().getRowVersion(), source.expectedOutlineVersion());
        return capture(projectId, context, source);
    }

    @Transactional(readOnly = true)
    public StylePreviewReview get(UUID projectId, UUID id) {
        contexts.requireOwnedProject(projectId);
        return requireReport(projectId, id);
    }

    @Transactional
    public StylePreviewReview save(UUID projectId, Snapshot source, QualityReviewContent content) {
        requireUnchanged(source, lockedSnapshot(projectId, source.source()));
        return reports.saveAndFlush(StylePreviewReview.create(projectId, source.hash(), source.source(), content));
    }

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

    @Transactional(readOnly = true)
    public void requireCurrent(UUID projectId, Snapshot source) {
        requireUnchanged(source, snapshot(projectId, source.source()));
    }

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

    private static void requireUnchanged(Snapshot previous, Snapshot current) {
        if (!previous.hash().equals(current.hash())) throw new IllegalStateException("检查期间试写依据已变化，请重新检查");
    }
}
