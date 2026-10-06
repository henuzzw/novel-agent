package com.novelagent.planning.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novelagent.planning.application.CharacterBlueprintCompletionService;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.StoryBibleService;
import com.novelagent.planning.domain.CharacterBlueprintFixtures;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.platform.api.ApiExceptionHandler;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class CharacterBlueprintControllerTest {
    private final StoryBibleService bibles = mock(StoryBibleService.class);
    private final CharacterBlueprintCompletionService characters = mock(CharacterBlueprintCompletionService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new StoryBibleController(bibles, characters))
            .setControllerAdvice(new ApiExceptionHandler()).build();
    private final UUID project = UUID.randomUUID();
    private final UUID source = UUID.randomUUID();
    private String path() { return "/api/v1/projects/" + project + "/story-bibles/" + source + "/actions/complete-characters"; }

    @Test void requiresExplicitVersionAndProvider() throws Exception {
        mvc.perform(post(path()).contentType(MediaType.APPLICATION_JSON).content("{\"provider\":\"DEEPSEEK\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post(path()).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(characters, bibles);
    }

    @Test void delegatesExactlyOneCompletionWithoutPublishing() throws Exception {
        var draft = StoryBibleVersion.create(UUID.randomUUID(), project, 2, "DEEPSEEK", null,
                null, null, CharacterBlueprintFixtures.bible(List.of(CharacterBlueprintFixtures.character("江澈"))));
        when(characters.complete(project, source, 3, ModelProvider.DEEPSEEK, null))
                .thenReturn(StoryBibleResponse.from(draft, draft.getContent()));
        mvc.perform(post(path()).header("If-Match", "\"3\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"DEEPSEEK\"}"))
                .andExpect(status().isCreated());
        verify(characters).complete(project, source, 3, ModelProvider.DEEPSEEK, null);
        verifyNoInteractions(bibles);
    }

    @Test void surfacesInvalidProviderWithoutCreatingDraft() throws Exception {
        when(characters.complete(any(), any(), anyLong(), any(), any()))
                .thenThrow(new IllegalArgumentException("人物补全请选择真实模型"));
        mvc.perform(post(path()).header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON)
                .content("{\"provider\":\"LOCAL_TEMPLATE\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(bibles);
    }
}
