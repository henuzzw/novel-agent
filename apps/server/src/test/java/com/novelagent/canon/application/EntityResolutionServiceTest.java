package com.novelagent.canon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class EntityResolutionServiceTest {
    private final UUID project = UUID.randomUUID();
    private final UUID commit = UUID.randomUUID();
    private final UUID entity = UUID.randomUUID();
    private final EntityResolutionStore store = mock(EntityResolutionStore.class);
    private final EntityResolutionService service = new EntityResolutionService(store);

    @Test
    void resolvesConfirmedAliasWithoutCreatingAnotherEntity() {
        when(store.aliasMatches(project, "CHARACTER", "小弦")).thenReturn(List.of(entity));
        assertThat(resolve("小弦", null)).isEqualTo(entity);
        verify(store, never()).create(any(), any(), anyLong(), anyString(), anyString(), anyString());
        assertThat(mention()).isEqualTo(new EntityResolutionStore.Mention(project, commit, "F1", "SUBJECT",
                "小弦", null, entity, "CONFIRMED_ALIAS", 0.98, "正文证据"));
    }

    @Test
    void rejectsUnresolvedPronounInsteadOfCreatingAnEntity() {
        assertThatThrownBy(() -> resolve("她", null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("无法确定代词");
        verify(store, never()).create(any(), any(), anyLong(), anyString(), anyString(), anyString());
        verify(store, never()).recordMention(any());
    }

    @Test
    void validatesRequestedStableIdWithinProjectAndType() {
        when(store.containsActive(project, entity, "CHARACTER")).thenReturn(true);
        assertThat(resolve("顾弦", entity.toString())).isEqualTo(entity);
        verify(store).containsActive(project, entity, "CHARACTER");
        verify(store, never()).canonicalMatches(any(), anyString(), anyString());
        assertThat(mention().method()).isEqualTo("REQUESTED_ID");
    }

    @Test
    void createsNewExplicitlyNamedEntityAndRecordsTheMention() {
        when(store.create(project, commit, 3, "CHARACTER", "顾弦", "正文证据")).thenReturn(entity);
        assertThat(resolve("顾弦", null)).isEqualTo(entity);
        assertThat(mention()).isEqualTo(new EntityResolutionStore.Mention(project, commit, "F1", "SUBJECT",
                "顾弦", null, entity, "CREATED", 0.85, "正文证据"));
    }

    @Test
    void canonicalNameTakesPriorityOverAliases() {
        when(store.canonicalMatches(project, "CHARACTER", "顾弦")).thenReturn(List.of(entity));
        assertThat(resolve("顾弦", null)).isEqualTo(entity);
        verify(store, never()).aliasMatches(any(), anyString(), anyString());
        assertThat(mention().method()).isEqualTo("CANONICAL_NAME");
    }

    @Test
    void ambiguousNamesOrAliasesDoNotCreateOrRecordGuessedEntities() {
        for (boolean canonical : List.of(true, false)) {
            var ambiguousStore = mock(EntityResolutionStore.class);
            var matches = List.of(entity, UUID.randomUUID());
            if (canonical) {
                when(ambiguousStore.canonicalMatches(project, "CHARACTER", "同名")).thenReturn(matches);
            } else {
                when(ambiguousStore.aliasMatches(project, "CHARACTER", "同名")).thenReturn(matches);
            }
            var resolver = new EntityResolutionService(ambiguousStore);
            assertThatThrownBy(() -> resolver.resolve(project, commit, 3, "F1", "SUBJECT", "CHARACTER", "同名", null, "证据"))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("多个对象");
            verify(ambiguousStore, never()).create(any(), any(), anyLong(), anyString(), anyString(), anyString());
            verify(ambiguousStore, never()).recordMention(any());
        }
    }

    @Test
    void invalidRequestedIdCannotFallBackToNameOrCreateAnotherEntity() {
        assertThatThrownBy(() -> resolve("顾弦", "not-a-uuid"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("格式不正确");
        verifyNoInteractions(store);
        assertThatThrownBy(() -> resolve("顾弦", entity.toString()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不属于当前项目");
        verify(store, never()).canonicalMatches(any(), anyString(), anyString());
        verify(store, never()).create(any(), any(), anyLong(), anyString(), anyString(), anyString());
        verify(store, never()).recordMention(any());
    }

    private UUID resolve(String name, String requestedId) {
        return service.resolve(project, commit, 3, "F1", "SUBJECT", "CHARACTER", name, requestedId, "正文证据");
    }

    private EntityResolutionStore.Mention mention() {
        var captured = ArgumentCaptor.forClass(EntityResolutionStore.Mention.class);
        verify(store).recordMention(captured.capture());
        return captured.getValue();
    }
}
