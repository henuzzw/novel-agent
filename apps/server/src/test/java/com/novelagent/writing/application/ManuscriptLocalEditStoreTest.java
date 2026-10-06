package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.domain.OutlineStatus;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.writing.api.ManuscriptLocalEditRequest;
import com.novelagent.writing.domain.ChapterContractStatus;
import com.novelagent.writing.domain.ChapterContractVersion;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptLocalEditSelection;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.infrastructure.ChapterContractVersionRepository;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ManuscriptLocalEditStoreTest {
    private final UUID id = UUID.randomUUID();
    private final UUID sourceId = UUID.randomUUID();
    private final NovelProject project = NovelProject.create(id, UUID.randomUUID(), "test", EntryMode.IDEA);
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final WritingContextService contexts = mock(WritingContextService.class);
    private final ManuscriptVersionRepository manuscripts = mock(ManuscriptVersionRepository.class);
    private final ChapterContractVersionRepository contracts = mock(ChapterContractVersionRepository.class);
    private final CharacterNameService names = mock(CharacterNameService.class);
    private final CharacterProfileService profiles = mock(CharacterProfileService.class);
    private final WritingStyleService styles = mock(WritingStyleService.class);
    private final EntityManager entities = mock(EntityManager.class);
    private final OutlineVersion outline = mock(OutlineVersion.class);
    private final StoryBibleVersion bible = mock(StoryBibleVersion.class);
    private final ManuscriptVersion source;
    private final ManuscriptLocalEditStore store;
    private final String token = "{{entity:" + UUID.randomUUID() + ":CANONICAL}}";

    ManuscriptLocalEditStoreTest() {
        UUID contractId = UUID.randomUUID();
        UUID outlineId = UUID.randomUUID();
        UUID bibleId = UUID.randomUUID();
        project.publishOutline(outlineId);
        project.publishStoryBible(bibleId);
        source = ManuscriptVersion.create(sourceId, id, contractId, 1, 4, "DEEPSEEK", null,
                new ManuscriptContent("title", "前\r\n" + token + " old\t后文", "summary", List.of("note")));
        source.accept();
        when(access.requireOwnedProject(id)).thenReturn(project);
        when(manuscripts.findByIdAndProjectIdAndChapterNumber(sourceId, id, 1)).thenReturn(Optional.of(source));
        when(manuscripts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(id, 1)).thenReturn(Optional.of(source));
        when(manuscripts.saveAndFlush(any())).thenAnswer(call -> call.getArgument(0));
        var contract = mock(ChapterContractVersion.class);
        when(contract.getId()).thenReturn(contractId);
        when(contract.getChapterNumber()).thenReturn(1);
        when(contract.getSourceOutlineVersionId()).thenReturn(outlineId);
        when(contract.getStatus()).thenReturn(ChapterContractStatus.APPROVED);
        when(contracts.findByIdAndProjectId(contractId, id)).thenReturn(Optional.of(contract));
        when(contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(id, 1)).thenReturn(Optional.of(contract));
        when(outline.getId()).thenReturn(outlineId);
        when(outline.getProjectId()).thenReturn(id);
        when(outline.getStatus()).thenReturn(OutlineStatus.PUBLISHED);
        when(bible.getId()).thenReturn(bibleId);
        when(bible.getStatus()).thenReturn(StoryBibleStatus.PUBLISHED);
        when(contexts.context(id, 1)).thenReturn(new WritingContextService.Context(outline, bible, null, null));
        when(names.render(eq(id), any(String.class))).thenAnswer(call -> call.getArgument(1));
        when(names.render(eq(id), any(ManuscriptContent.class))).thenAnswer(call -> {
            ManuscriptContent value = call.getArgument(1);
            return new ManuscriptContent(value.title(), value.body().replace(token, "Alice"), value.summary(), value.continuityNotes());
        });
        when(names.tokenize(eq(id), any(ManuscriptContent.class))).thenAnswer(call -> {
            ManuscriptContent value = call.getArgument(1);
            return new ManuscriptContent(value.title(), value.body().replace("Alice", token), value.summary(), value.continuityNotes());
        });
        when(profiles.promptContext(id)).thenReturn("profiles");
        when(styles.promptContext(id)).thenReturn("style");
        store = new ManuscriptLocalEditStore(access, contexts, manuscripts, contracts, names, profiles, styles, entities, new ObjectMapper());
    }

    private ManuscriptLocalEditRequest request() {
        return new ManuscriptLocalEditRequest(sourceId, 0L, "old", 1, 9, ModelProvider.DEEPSEEK, "clarify", true);
    }

    @Test
    void createsNewDraftAndProvesRenderedPrefixSuffixAndMetadataAreUnchanged() {
        var snapshot = store.snapshot(id, 1, sourceId, 0);
        var selection = ManuscriptLocalEditSelection.resolve(snapshot.rendered().body(), "old", null, 1);
        var result = store.save(snapshot, selection, "新的", request());
        assertThat(result.content().body()).isEqualTo("前\r\nAlice 新的\t后文");
        assertThat(result.content().title()).isEqualTo(snapshot.rendered().title());
        assertThat(result.content().summary()).isEqualTo(snapshot.rendered().summary());
        assertThat(result.content().continuityNotes()).isEqualTo(snapshot.rendered().continuityNotes());
        assertThat(result.status()).isEqualTo(ManuscriptStatus.DRAFT);
        assertThat(result.baseManuscriptVersionId()).isEqualTo(sourceId);
        assertThat(result.id()).isNotEqualTo(sourceId);
        assertThat(source.getContent().body()).contains("old");
        assertThat(source.getStatus()).isEqualTo(ManuscriptStatus.AUTHOR_ACCEPTED);
        assertThat(project.getCurrentCanonVersion()).isZero();
        var saved = ArgumentCaptor.forClass(ManuscriptVersion.class);
        verify(manuscripts).saveAndFlush(saved.capture());
        assertThat(saved.getValue().getContent().body()).contains(token);
        verify(entities).refresh(project, LockModeType.PESSIMISTIC_WRITE);
        verify(entities).refresh(source, LockModeType.PESSIMISTIC_WRITE);
    }

    @Test
    void rejectsChangedCanonStyleOutlineOrBibleBeforeSaving() {
        for (String change : List.of("canon", "style", "outline", "bible")) {
            var fixture = new ManuscriptLocalEditStoreTest();
            var snapshot = fixture.store.snapshot(fixture.id, 1, fixture.sourceId, 0);
            switch (change) {
                case "canon" -> fixture.project.commitCanon(0);
                case "style" -> when(fixture.styles.promptContext(fixture.id)).thenReturn("changed");
                case "outline" -> when(fixture.outline.getRowVersion()).thenReturn(1L);
                case "bible" -> when(fixture.bible.getRowVersion()).thenReturn(1L);
                default -> throw new AssertionError(change);
            }
            var selection = ManuscriptLocalEditSelection.resolve(snapshot.rendered().body(), "old", null, 1);
            assertThatThrownBy(() -> fixture.store.save(snapshot, selection, "new", fixture.request()))
                    .isInstanceOf(ManuscriptLocalEditConflictException.class);
            verify(fixture.manuscripts, never()).saveAndFlush(any());
        }
    }

    @Test
    void rejectsNameTokenizationThatChangesAnyDisplayedCharacter() {
        var snapshot = store.snapshot(id, 1, sourceId, 0);
        when(names.tokenize(eq(id), any(ManuscriptContent.class))).thenReturn(new ManuscriptContent("title", "tampered outside", "summary", List.of("note")));
        var selection = ManuscriptLocalEditSelection.resolve(snapshot.rendered().body(), "old", null, 1);
        assertThatThrownBy(() -> store.save(snapshot, selection, "new", request())).isInstanceOf(ManuscriptLocalEditConflictException.class);
        verify(manuscripts, never()).saveAndFlush(any());
    }

    @Test
    void rejectsStaleSourceRowVersionBeforeAnyPersistence() {
        assertThatThrownBy(() -> store.snapshot(id, 1, sourceId, 1)).isInstanceOf(ResourceVersionConflictException.class);
        verify(manuscripts, never()).saveAndFlush(any());
    }
}
