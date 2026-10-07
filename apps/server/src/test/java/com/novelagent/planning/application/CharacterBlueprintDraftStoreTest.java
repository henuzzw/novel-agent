package com.novelagent.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.planning.domain.CharacterBlueprintFixtures;
import com.novelagent.planning.domain.StoryBibleContent;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CharacterBlueprintDraftStoreTest {
    private final UUID projectId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();
    private final NovelProjectRepository projects = mock(NovelProjectRepository.class);
    private final StoryBibleVersionRepository bibles = mock(StoryBibleVersionRepository.class);
    private final CurrentActorProvider actor = mock(CurrentActorProvider.class);
    private final CharacterNameService names = mock(CharacterNameService.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final NovelProject project = mock(NovelProject.class);
    private final CharacterBlueprintDraftStore store = new CharacterBlueprintDraftStore(bibles, new ProjectAccessService(projects, actor), names, entities);

    @BeforeEach void setUp() {
        when(actor.currentUserId()).thenReturn(ownerId);
        when(project.getOwnerId()).thenReturn(ownerId);
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(names.render(eq(projectId), any(StoryBibleContent.class), eq(StoryBibleContent.class)))
                .thenAnswer(call -> call.getArgument(1));
        when(bibles.saveAndFlush(any(StoryBibleVersion.class))).thenAnswer(call -> call.getArgument(0));
    }

    @Test void completionPreservesImportSourcePublishedBibleAndCurrentPointers() {
        var importId = UUID.randomUUID();
        var bible = StoryBibleVersion.createFromImport(UUID.randomUUID(), projectId, 1, "LOCAL_CODEX", null,
                importId, CharacterBlueprintFixtures.bible(List.of()));
        bible.publish();
        setupBible(bible);
        var source = store.load(projectId, bible.getId(), 0);
        var result = store.save(source, List.of(CharacterBlueprintFixtures.character("江澈")), ModelProvider.DEEPSEEK, null);
        assertThat(result.status()).isEqualTo(StoryBibleStatus.DRAFT);
        assertThat(result.baseBibleVersionId()).isEqualTo(bible.getId());
        assertThat(result.sourceImportId()).isEqualTo(importId);
        assertThat(result.schemaVersion()).isEqualTo("story-bible/2");
        assertThat(bible.getContent().characterBlueprints()).isEmpty();
        assertThat(bible.getStatus()).isEqualTo(StoryBibleStatus.PUBLISHED);
        verify(entities).refresh(project, LockModeType.PESSIMISTIC_WRITE);
        verify(entities).refresh(bible, LockModeType.PESSIMISTIC_WRITE);
        verify(project, never()).publishStoryBible(any());
        verify(projects, never()).save(any());
    }

    @Test void rejectsForeignProjectsStaleVersionsAndChangedNamesBeforeSaving() {
        var bible = StoryBibleVersion.create(UUID.randomUUID(), projectId, 1, "LOCAL_CODEX", null,
                UUID.randomUUID(), UUID.randomUUID(), CharacterBlueprintFixtures.bible(List.of()));
        setupBible(bible);
        assertThatThrownBy(() -> store.load(projectId, bible.getId(), 4)).isInstanceOf(ResourceVersionConflictException.class);
        var source = store.load(projectId, bible.getId(), 0);
        when(names.render(eq(projectId), any(StoryBibleContent.class), eq(StoryBibleContent.class)))
                .thenReturn(CharacterBlueprintFixtures.bible(List.of(CharacterBlueprintFixtures.character("已改名"))));
        assertThatThrownBy(() -> store.save(source, List.of(CharacterBlueprintFixtures.character("江澈")), ModelProvider.DEEPSEEK, null))
                .hasMessageContaining("姓名已变化");
        when(project.getOwnerId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> store.load(projectId, bible.getId(), 0)).isInstanceOf(ProjectNotFoundException.class);
        verify(bibles, never()).saveAndFlush(any());
    }

    @Test void doesNotCreateFakeVersionWhenNothingChanges() {
        var character = CharacterBlueprintFixtures.character("江澈");
        var bible = StoryBibleVersion.create(UUID.randomUUID(), projectId, 1, "LOCAL_CODEX", null,
                UUID.randomUUID(), UUID.randomUUID(), CharacterBlueprintFixtures.bible(List.of(character)));
        setupBible(bible);
        var source = store.load(projectId, bible.getId(), 0);
        assertThatThrownBy(() -> store.save(source, List.of(character), ModelProvider.LOCAL_CODEX, null))
                .hasMessageContaining("未创建新版本");
        verify(bibles, never()).saveAndFlush(any());
    }

    @Test
    void rechecksOwnershipAfterProjectRefreshBeforeTouchingTheBible() {
        var source = new CharacterBlueprintDraftStore.Source(
                projectId, UUID.randomUUID(), 0,
                CharacterBlueprintFixtures.bible(List.of()), CharacterBlueprintFixtures.bible(List.of()));
        doAnswer(call -> {
            when(project.getOwnerId()).thenReturn(UUID.randomUUID());
            return null;
        }).when(entities).refresh(project, LockModeType.PESSIMISTIC_WRITE);

        assertThatThrownBy(() -> store.save(source,
                List.of(CharacterBlueprintFixtures.character("江澈")), ModelProvider.DEEPSEEK, null))
                .isInstanceOf(ProjectNotFoundException.class);
        verify(bibles, never()).findByIdAndProjectId(any(), any());
        verify(bibles, never()).saveAndFlush(any());
    }

    private void setupBible(StoryBibleVersion bible) {
        when(bibles.findByIdAndProjectId(bible.getId(), projectId)).thenReturn(Optional.of(bible));
        when(bibles.findFirstByProjectIdOrderByGenerationNumberDesc(projectId)).thenReturn(Optional.of(bible));
    }
}
