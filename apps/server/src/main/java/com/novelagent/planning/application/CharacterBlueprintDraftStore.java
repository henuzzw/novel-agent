package com.novelagent.planning.application;

import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.planning.api.StoryBibleResponse;
import com.novelagent.planning.domain.CharacterBlueprint;
import com.novelagent.planning.domain.CharacterBlueprintCompletion;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 人物补全草稿。
 *
 * <p>提供补全前的只读来源和补全后的短事务保存。锁内复核圣经行版本及来源，合并仅允许补空白或新增人物，不直接写独立人物正史。</p>
 */
@Service
public class CharacterBlueprintDraftStore {
    public record Source(UUID projectId, UUID bibleId, long rowVersion,
            StoryBibleContent content, StoryBibleContent rendered) { }

    private final StoryBibleVersionRepository bibles;
    private final ProjectAccessService access;
    private final CharacterNameService names;
    private final EntityManager entities;

    public CharacterBlueprintDraftStore(
            StoryBibleVersionRepository bibles,
            ProjectAccessService access,
            CharacterNameService names,
            EntityManager entities) {
        this.bibles = bibles;
        this.access = access;
        this.names = names;
        this.entities = entities;
    }

    /**
     * 读取待补全的源圣经及必要上下文，校验作者给出的源行版本，不调用模型。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param bibleId 故事圣经版本 ID。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     */
    @Transactional(readOnly = true)
    public Source load(UUID projectId, UUID bibleId, long expectedVersion) {
        access.requireOwnedProject(projectId);
        var bible = requireBible(projectId, bibleId);
        checkVersion(bible, expectedVersion);
        return new Source(projectId, bibleId, bible.getRowVersion(), bible.getContent(),
                names.render(projectId, bible.getContent(), StoryBibleContent.class));
    }

    /**
     * 保存本步骤已校验的业务结果，保留来源关联；并发条件和事务范围由该存储方法及调用方约定控制。
     *
     * @param source 生成或检查前读取的来源快照，用于保存时再次复核。
     * @param proposed 本次提出、尚待校验或作者确认的数据。
     * @param provider 明确选择的生成供应商；本地模板不代表真实文学生成。
     * @param instruction 作者本次要求，只能在已有事实与授权边界内执行。
     */
    @Transactional
    public StoryBibleResponse save(Source source, List<CharacterBlueprint> proposed, ModelProvider provider,
            String instruction) {
        var project = access.requireOwnedProject(source.projectId());
        entities.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        access.requireOwnedProject(project);
        var bible = requireBible(source.projectId(), source.bibleId());
        entities.refresh(bible, LockModeType.PESSIMISTIC_WRITE);
        checkVersion(bible, source.rowVersion());
        if (!bible.getContent().equals(source.content())
                || !names.render(source.projectId(), bible.getContent(), StoryBibleContent.class).equals(source.rendered())) {
            throw new IllegalArgumentException("人物补全期间圣经或人物姓名已变化，请刷新后重试");
        }
        var merged = CharacterBlueprintCompletion.merge(source.content(), source.rendered(), proposed);
        if (merged.equals(source.content())) throw new IllegalArgumentException("没有可补充的人物字段，未创建新版本");
        int generation = bibles.findFirstByProjectIdOrderByGenerationNumberDesc(source.projectId())
                .map(value -> value.getGenerationNumber() + 1).orElse(1);
        var draft = bible.getSourceImportId() == null
                ? StoryBibleVersion.create(UUID.randomUUID(), source.projectId(), generation, provider.name(), instruction,
                        bible.getSourceDirectionSetId(), bible.getSourceCandidateId(), bible.getId(), merged,
                        List.of("补全缺失人物底稿；已有字段和其他圣经内容保持不变。"))
                : StoryBibleVersion.createFromImport(UUID.randomUUID(), source.projectId(), generation, provider.name(),
                        instruction, bible.getSourceImportId(), bible.getId(), merged);
        bibles.saveAndFlush(draft);
        return StoryBibleResponse.from(draft, names.render(source.projectId(), merged, StoryBibleContent.class));
    }


    private StoryBibleVersion requireBible(UUID projectId, UUID bibleId) {
        return bibles.findByIdAndProjectId(bibleId, projectId)
                .orElseThrow(() -> new StoryBibleVersionNotFoundException(bibleId));
    }

    private static void checkVersion(StoryBibleVersion bible, long expected) {
        if (expected < 0 || bible.getRowVersion() != expected) {
            throw new ResourceVersionConflictException(expected, bible.getRowVersion());
        }
    }
}
