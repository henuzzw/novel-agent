package com.novelagent.canon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.novelagent.canon.api.dto.CharacterProfileResponse;
import com.novelagent.canon.api.dto.UpdateCharacterProfileRequest;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CharacterProfileStoreBoundaryTest {
    private final UUID project = UUID.randomUUID();
    private final UUID character = UUID.randomUUID();
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final CharacterProfileStore store = mock(CharacterProfileStore.class);
    private final CharacterProfileService service = new CharacterProfileService(access, store);
    private final UpdateCharacterProfileRequest changes = new UpdateCharacterProfileRequest(
            null, null, "身份", null, null, null, null, null, null, null, null, null, null, null, null, null, null);

    @Test
    void listingChecksOwnershipBeforeCreatingMissingProfiles() {
        when(store.profiles(project)).thenReturn(List.of());
        assertThat(service.list(project)).isEmpty();
        var order = inOrder(access, store);
        order.verify(access).requireOwnedProject(project);
        order.verify(store).ensureMissingProfiles(project);
        order.verify(store).profiles(project);
    }

    @Test
    void deniedAccessDoesNotReadOrWriteProfiles() {
        when(access.requireOwnedProject(project)).thenThrow(new ProjectNotFoundException(project));
        assertThatThrownBy(() -> service.list(project)).isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> service.update(project, character, 2, changes)).isInstanceOf(ProjectNotFoundException.class);
        verifyNoInteractions(store);
    }

    @Test
    void missingOrRetiredCharacterIsRejectedBeforeProfileCreation() {
        when(store.containsActiveCharacter(project, character)).thenReturn(false);
        assertThatThrownBy(() -> service.update(project, character, 2, changes))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("人物不存在");
        verify(store, never()).ensureProfile(project, character);
        verify(store, never()).update(project, character, 2, changes);
    }

    @Test
    void successfulUpdateUsesExpectedVersionAndReturnsStoredValue() {
        var saved = mock(CharacterProfileResponse.class);
        when(store.containsActiveCharacter(project, character)).thenReturn(true);
        when(store.update(project, character, 2, changes)).thenReturn(1);
        when(store.find(project, character)).thenReturn(Optional.of(saved));
        assertThat(service.update(project, character, 2, changes)).isSameAs(saved);
        var order = inOrder(access, store);
        order.verify(access).requireOwnedProject(project);
        order.verify(store).containsActiveCharacter(project, character);
        order.verify(store).ensureProfile(project, character);
        order.verify(store).update(project, character, 2, changes);
        order.verify(store).find(project, character);
    }

    @Test
    void versionConflictReportsCurrentVersionWithoutRetryingTheWrite() {
        var current = mock(CharacterProfileResponse.class);
        when(current.version()).thenReturn(7L);
        when(store.containsActiveCharacter(project, character)).thenReturn(true);
        when(store.find(project, character)).thenReturn(Optional.of(current));
        assertThatThrownBy(() -> service.update(project, character, 2, changes))
                .isInstanceOfSatisfying(ResourceVersionConflictException.class, error -> {
                    assertThat(error.getExpectedVersion()).isEqualTo(2);
                    assertThat(error.getActualVersion()).isEqualTo(7);
                });
        verify(store).update(project, character, 2, changes);
    }
}
