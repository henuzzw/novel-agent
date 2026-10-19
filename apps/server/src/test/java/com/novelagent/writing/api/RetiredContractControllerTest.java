package com.novelagent.writing.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.platform.api.ApiExceptionHandler;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.writing.application.ChapterContractService;
import com.novelagent.writing.application.ChapterReviewService;
import com.novelagent.writing.application.ManuscriptService;
import com.novelagent.writing.application.WritingContextService;
import com.novelagent.writing.application.WritingService;
import com.novelagent.writing.domain.ChapterContractVersion;
import com.novelagent.writing.infrastructure.ChapterContractReviewVersionRepository;
import com.novelagent.writing.infrastructure.ChapterContractVersionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class RetiredContractControllerTest {
    private final UUID projectId = UUID.randomUUID();
    private final UUID contractId = UUID.randomUUID();
    private final String base = "/api/v1/projects/" + projectId + "/chapters/1";
    private final WritingContextService contexts = mock(WritingContextService.class);
    private final ChapterContractVersionRepository contracts = mock(ChapterContractVersionRepository.class);
    private final ChapterContractReviewVersionRepository contractReviews = mock(ChapterContractReviewVersionRepository.class);
    private final CharacterNameService names = mock(CharacterNameService.class);
    private final ManuscriptService manuscripts = mock(ManuscriptService.class);
    private final ChapterReviewService reviews = mock(ChapterReviewService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new WritingController(new WritingService(
            new ChapterContractService(contexts, contracts, contractReviews, names), manuscripts, reviews)))
            .setControllerAdvice(new ApiExceptionHandler()).build();

    @Test void retiredWritesCheckOwnershipAndReturnGoneWithoutParsingOldPayloads() throws Exception {
        when(contexts.requireOwnedProject(projectId)).thenThrow(new ProjectNotFoundException(projectId));
        for (Route route : retiredRoutes()) {
            mvc.perform(request(route.method(), base + route.path())).andExpect(status().isNotFound());
        }
        verify(contexts, times(5)).requireOwnedProject(projectId);
        verifyNoInteractions(contracts, contractReviews, names, manuscripts, reviews);
        reset(contexts);

        for (Route route : retiredRoutes()) {
            mvc.perform(request(route.method(), base + route.path())).andExpect(status().isGone());
        }
        verify(contexts, times(5)).requireOwnedProject(projectId);
        verifyNoInteractions(contracts, contractReviews, names, manuscripts, reviews);

        for (Route route : retiredRoutes()) {
            for (String body : List.of("{\"content\":{}}", "{broken")) {
                mvc.perform(request(route.method(), base + route.path()).contentType(MediaType.APPLICATION_JSON)
                        .header("If-Match", "obsolete-version").content(body)).andExpect(status().isGone());
            }
        }
        verify(contexts, times(15)).requireOwnedProject(projectId);
        verifyNoInteractions(contracts, contractReviews, names, manuscripts, reviews);
    }

    @Test void historicalReadsKeepTheirRoutesAndVersionHeader() throws Exception {
        ChapterContractVersion version = mock(ChapterContractVersion.class);
        when(version.getId()).thenReturn(contractId);
        when(version.getRowVersion()).thenReturn(7L);
        when(contracts.findFirstByProjectIdAndChapterNumberOrderByVersionNumberDesc(projectId, 1))
                .thenReturn(Optional.of(version));
        when(contracts.findByIdAndProjectIdAndChapterNumber(contractId, projectId, 1))
                .thenReturn(Optional.of(version));
        mvc.perform(get(base + "/contracts/latest")).andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"7\""))
                .andExpect(jsonPath("$.id").value(contractId.toString()));
        mvc.perform(get(base + "/contracts/" + contractId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(contractId.toString()));
        mvc.perform(get(base + "/contracts")).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());
        mvc.perform(get(base + "/contract-reviews/latest")).andExpect(status().isNoContent());
        verify(contexts, times(4)).requireOwnedProject(projectId);
        verifyNoInteractions(manuscripts, reviews);
    }

    private List<Route> retiredRoutes() {
        return List.of(new Route(HttpMethod.POST, "/contracts/actions/generate"),
                new Route(HttpMethod.PUT, "/contracts/" + contractId),
                new Route(HttpMethod.POST, "/contracts/" + contractId + "/actions/approve"),
                new Route(HttpMethod.POST, "/contract-reviews/actions/generate"),
                new Route(HttpMethod.POST, "/contract-reviews/" + contractId + "/actions/approve"));
    }

    private record Route(HttpMethod method, String path) {}
}
