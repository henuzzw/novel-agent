package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WritingStyleServiceTest {
    @Test void roundTripsProfileWithoutRemovingOtherSettingsAndAllowsClearing() {
        UUID id = UUID.randomUUID();
        UUID owner = UUID.randomUUID();
        var project = NovelProject.create(id, owner, "小说", EntryMode.IDEA);
        project.setSetting("unrelated", java.util.Map.of("enabled", true));
        var repo = mock(NovelProjectRepository.class);
        var actors = mock(CurrentActorProvider.class);
        when(repo.findById(id)).thenReturn(Optional.of(project));
        when(actors.currentUserId()).thenReturn(owner);
        var catalog = mock(com.novelagent.writing.infrastructure.WritingStylePresetCatalog.class);
        when(catalog.resolve(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));
        var service = new WritingStyleService(repo, new ProjectAccessService(repo, actors), new ObjectMapper(), catalog);
        var profile = WritingStylePresets.all().getFirst();
        service.apply(id, profile, 0);
        assertThat(service.get(id).profile()).isEqualTo(profile);
        assertThat(service.promptContext(id)).contains(profile.name());
        assertThat(service.promptContext(id)).endsWith(WritingStyleGuide.render(profile));
        assertThatThrownBy(() -> service.apply(id, null, 7)).isInstanceOf(ResourceVersionConflictException.class);
        service.apply(id, null, 0);
        assertThat(service.get(id).profile()).isNull();
        assertThat(service.promptContext(id)).isEqualTo("未指定；沿用故事圣经中的叙事风格。");
        assertThat(project.getSetting("unrelated")).isEqualTo(java.util.Map.of("enabled", true));
        when(actors.currentUserId()).thenReturn(UUID.randomUUID());
        assertThatThrownBy(() -> service.get(id)).isInstanceOf(ProjectNotFoundException.class);
        assertThatThrownBy(() -> service.apply(id, profile, 0)).isInstanceOf(ProjectNotFoundException.class);
    }
}
