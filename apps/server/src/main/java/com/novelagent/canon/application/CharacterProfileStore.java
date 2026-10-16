package com.novelagent.canon.application;

import com.novelagent.canon.api.CharacterProfileResponse;
import com.novelagent.canon.api.UpdateCharacterProfileRequest;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Project-scoped storage operations; callers own authorization and transaction boundaries. */
public interface CharacterProfileStore {
    void ensureMissingProfiles(UUID projectId);

    void ensureProfile(UUID projectId, UUID characterId);

    boolean containsActiveCharacter(UUID projectId, UUID characterId);

    int update(UUID projectId, UUID characterId, long expectedVersion, UpdateCharacterProfileRequest value);

    List<CharacterProfileResponse> profiles(UUID projectId);

    List<CharacterProfileResponse> profiles(UUID projectId, Collection<UUID> characterIds);

    Optional<CharacterProfileResponse> find(UUID projectId, UUID characterId);

    List<CharacterReference> references(UUID projectId);

    record CharacterReference(UUID id, String roleKey, List<String> names) {
        public CharacterReference {
            names = List.copyOf(names);
        }
    }
}
