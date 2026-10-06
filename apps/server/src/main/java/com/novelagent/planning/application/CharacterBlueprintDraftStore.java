package com.novelagent.planning.application;

import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.planning.api.StoryBibleResponse;
import com.novelagent.planning.domain.CharacterBlueprint;
import com.novelagent.planning.domain.CharacterBlueprintCompletion;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CharacterBlueprintDraftStore {
    public record Source(UUID projectId, UUID bibleId, long rowVersion,
            StoryBibleContent content, StoryBibleContent rendered) { }

    private final NovelProjectRepository projects;
    private final StoryBibleVersionRepository bibles;
    private final CurrentActorProvider actor;
    private final CharacterNameService names;
    private final EntityManager entities;

    public CharacterBlueprintDraftStore(NovelProjectRepository projects, StoryBibleVersionRepository bibles,
            CurrentActorProvider actor, CharacterNameService names, EntityManager entities) {
        this.projects = projects; this.bibles = bibles; this.actor = actor; this.names = names; this.entities = entities;
    }

    @Transactional(readOnly = true)
    public Source load(UUID projectId, UUID bibleId, long expectedVersion) {
        owned(projectId);
        var bible = requireBible(projectId, bibleId);
        checkVersion(bible, expectedVersion);
        return new Source(projectId, bibleId, bible.getRowVersion(), bible.getContent(),
                names.render(projectId, bible.getContent(), StoryBibleContent.class));
    }

    @Transactional
    public StoryBibleResponse save(Source source, List<CharacterBlueprint> proposed, ModelProvider provider,
            String instruction) {
        var project = owned(source.projectId());
        entities.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        if (!project.getOwnerId().equals(actor.currentUserId())) throw new ProjectNotFoundException(source.projectId());
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

    private NovelProject owned(UUID projectId) {
        return projects.findById(projectId).filter(value -> value.getOwnerId().equals(actor.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
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
