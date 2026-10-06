package com.novelagent.writing.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.writing.api.ManuscriptLocalEditRequest;
import com.novelagent.writing.api.ManuscriptResponse;
import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptLocalEditSelection;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.infrastructure.ChapterContractVersionRepository;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManuscriptLocalEditStore {
    private final ProjectAccessService access;
    private final WritingContextService contexts;
    private final ManuscriptVersionRepository manuscripts;
    private final ChapterContractVersionRepository contracts;
    private final CharacterNameService names;
    private final CharacterProfileService profiles;
    private final WritingStyleService styles;
    private final EntityManager entities;
    private final ObjectMapper mapper;

    public ManuscriptLocalEditStore(ProjectAccessService access, WritingContextService contexts,
            ManuscriptVersionRepository manuscripts, ChapterContractVersionRepository contracts,
            CharacterNameService names, CharacterProfileService profiles, WritingStyleService styles,
            EntityManager entities, ObjectMapper mapper) {
        this.access = access;
        this.contexts = contexts;
        this.manuscripts = manuscripts;
        this.contracts = contracts;
        this.names = names;
        this.profiles = profiles;
        this.styles = styles;
        this.entities = entities;
        this.mapper = mapper;
    }

    public record Snapshot(UUID projectId, int chapter, UUID sourceId, long sourceRowVersion,
            UUID contractId, ManuscriptContent rendered, String context, String fingerprint) {
    }

    @Transactional(readOnly = true)
    public Snapshot snapshot(UUID projectId, int chapter, UUID sourceId, long expected) {
        return read(projectId, chapter, sourceId, expected, false);
    }

    private Snapshot read(UUID projectId, int chapter, UUID sourceId, long expected, boolean lock) {
        NovelProject project = access.requireOwnedProject(projectId);
        if (lock) entities.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        ManuscriptVersion source = manuscripts.findByIdAndProjectIdAndChapterNumber(sourceId, projectId, chapter)
                .orElseThrow(() -> new WritingResourceNotFoundException("正文版本", sourceId));
        if (lock) entities.refresh(source, LockModeType.PESSIMISTIC_WRITE);
        WritingChecks.check(source.getRowVersion(), expected);
        var contract = contracts.findByIdAndProjectId(source.getSourceContractVersionId(), projectId)
                .orElseThrow(() -> new ManuscriptLocalEditConflictException("源稿合同已失效"));
        if (lock) entities.refresh(contract, LockModeType.PESSIMISTIC_READ);
        WritingContextService.Context basis;
        try {
            basis = contexts.context(projectId, chapter);
        } catch (IllegalArgumentException failure) {
            throw new ManuscriptLocalEditConflictException("当前已发布大纲或圣经已失效，请刷新来源");
        }
        if (lock) {
            entities.refresh(basis.outline(), LockModeType.PESSIMISTIC_READ);
            entities.refresh(basis.bible(), LockModeType.PESSIMISTIC_READ);
            basis = contexts.context(projectId, chapter);
        }
        if (contract.getChapterNumber() != chapter || contract.getStatus() != ChapterContractStatus.APPROVED
                || !contract.getSourceOutlineVersionId().equals(basis.outline().getId())
                || !contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, chapter)
                        .map(value -> value.getId().equals(contract.getId())).orElse(false)) {
            throw new ManuscriptLocalEditConflictException("源稿合同或已发布大纲已更新，请刷新来源");
        }
        ManuscriptContent rendered = names.render(projectId, source.getContent());
        String context = names.render(projectId, mapper.valueToTree(basis.bible().getContent()).toString())
                + "\n合同：" + names.render(projectId, mapper.valueToTree(contract.getContent()).toString())
                + "\n当前章与相邻章边界：" + names.render(projectId, basis.toString())
                + "\n人物档案：" + names.render(projectId, profiles.promptContext(projectId))
                + "\n写作风格：" + styles.promptContext(projectId)
                + "\n创作策略：" + basis.creativeStrategy()
                + "\n正史水位：" + project.getCurrentCanonVersion();
        String fingerprint = NovelMemoryContext.fingerprint(context + "\n" + mapper.valueToTree(rendered)
                + "\n" + mapper.valueToTree(source.getContent()) + "\n" + project.getRowVersion()
                + "\n" + basis.boundaryFingerprint() + "\n" + mapper.valueToTree(basis.outline().getContent())
                + "\n" + contract.getId() + ":" + contract.getRowVersion());
        return new Snapshot(projectId, chapter, sourceId, expected, contract.getId(), rendered, context, fingerprint);
    }

    @Transactional
    public ManuscriptResponse save(Snapshot source, ManuscriptLocalEditSelection selection, String replacement,
            ManuscriptLocalEditRequest request) {
        Snapshot current = read(source.projectId(), source.chapter(), source.sourceId(), source.sourceRowVersion(), true);
        if (!current.fingerprint().equals(source.fingerprint())) {
            throw new ManuscriptLocalEditConflictException("局部编辑期间源稿、合同、规划、正史、风格或策略已变化，请重新选择");
        }
        ManuscriptContent displayed = new ManuscriptContent(current.rendered().title(),
                selection.replace(current.rendered().body(), replacement), current.rendered().summary(),
                current.rendered().continuityNotes());
        ManuscriptContent encoded = names.tokenize(source.projectId(), displayed);
        // Name normalization must not change even one displayed character outside the exact splice.
        if (!displayed.equals(names.render(source.projectId(), encoded))) {
            throw new ManuscriptLocalEditConflictException("姓名引用转换改变了显示正文，已拒绝保存");
        }
        int version = manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(source.projectId(), source.chapter())
                .map(value -> value.getVersionNumber() + 1).orElse(1);
        String provenance = mapper.valueToTree(request).toString() + "\n依据=" + source.fingerprint();
        ManuscriptVersion draft = ManuscriptVersion.create(UUID.randomUUID(), source.projectId(), source.contractId(),
                source.chapter(), version, "LOCAL_EDIT_" + request.provider().name(), provenance, source.sourceId(), encoded,
                List.of("仅替换第 " + selection.occurrence() + " 处精确选区（UTF-16 偏移 " + selection.offset() + "）"));
        ManuscriptVersion saved = manuscripts.saveAndFlush(draft);
        return ManuscriptResponse.from(saved, names.render(source.projectId(), saved.getContent()));
    }
}
