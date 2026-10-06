package com.novelagent.planning.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.planning.domain.CharacterBlueprint;
import com.novelagent.planning.domain.CharacterBlueprintFixtures;
import com.novelagent.planning.domain.StoryBibleStatus;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.planning.infrastructure.StoryBibleVersionRepository;
import com.novelagent.project.application.ProjectAccessService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.jdbc.core.JdbcTemplate;

class PlanningMaterialProfileSyncTest {
    @Test void publishedDemographicsUseTheSameAuthorPreservingBlankFillAsOtherFields() {
        UUID project = UUID.randomUUID(), character = UUID.randomUUID();
        var json = new ObjectMapper();
        ObjectNode node = json.valueToTree(CharacterBlueprintFixtures.character("江澈"));
        node.put("gender", "男").put("ageDescription", "16岁");
        var blueprint = json.convertValue(node, CharacterBlueprint.class);
        var jdbc = mock(JdbcTemplate.class, invocation -> invocation.getMethod().getName().equals("query")
                ? List.of(character) : Answers.RETURNS_DEFAULTS.answer(invocation));
        var names = mock(CharacterNameService.class);
        var service = new PlanningMaterialSyncService(mock(ProjectAccessService.class), names,
                mock(StoryBibleVersionRepository.class), mock(OutlineVersionRepository.class), jdbc, json);
        var bible = mock(StoryBibleVersion.class);
        when(bible.getProjectId()).thenReturn(project);
        when(bible.getId()).thenReturn(UUID.randomUUID());
        when(bible.getStatus()).thenReturn(StoryBibleStatus.PUBLISHED);
        when(bible.getContent()).thenReturn(CharacterBlueprintFixtures.bible(List.of(blueprint)));
        service.syncBible(bible);
        verify(names).initializeFromStoryBible(project, bible);
        var writes = mockingDetails(jdbc).getInvocations().stream()
                .filter(call -> call.getMethod().getName().equals("update"))
                .filter(call -> ((String) call.getArgument(0)).startsWith("UPDATE character_profile SET"))
                .toList();
        assertThat(writes).hasSize(16);
        assertThat(writes).anySatisfy(call -> {
            assertThat((String) call.getArgument(0)).contains("SET gender = ?", "NULLIF(btrim(gender), '') IS NULL");
            assertThat((Object) call.getArgument(1)).isEqualTo("男");
        }).anySatisfy(call -> {
            assertThat((String) call.getArgument(0)).contains("SET age_description = ?", "NULLIF(btrim(age_description), '') IS NULL");
            assertThat((Object) call.getArgument(1)).isEqualTo("16岁");
        });
    }
}
