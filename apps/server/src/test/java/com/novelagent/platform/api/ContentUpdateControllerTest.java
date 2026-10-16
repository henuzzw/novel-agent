package com.novelagent.platform.api;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.novelagent.planning.api.OutlineController;
import com.novelagent.planning.api.OutlineResponse;
import com.novelagent.planning.application.OutlineService;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.writing.api.ChapterReviewResponse;
import com.novelagent.writing.api.ManuscriptResponse;
import com.novelagent.writing.api.WritingController;
import com.novelagent.writing.application.WritingService;
import com.novelagent.writing.domain.ChapterReviewContent;
import com.novelagent.writing.domain.ManuscriptContent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ContentUpdateControllerTest {
    private final UUID project = UUID.randomUUID();
    private final UUID id = UUID.randomUUID();
    private final String base = "/api/v1/projects/" + project;
    private final OutlineService outlines = mock(OutlineService.class);
    private final WritingService writing = mock(WritingService.class);
    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new OutlineController(outlines),
            new WritingController(writing)).setControllerAdvice(new ApiExceptionHandler()).build();

    @Test void sharedEnvelopeBindsEachConcreteContentTypeAndKeepsEtags() throws Exception {
        var outline = new OutlineContent("大纲", null, null, null, 0, 0, List.of());
        var manuscript = new ManuscriptContent("正文", "内容", "摘要", List.of());
        var review = new ChapterReviewContent("审阅", List.of(), List.of());
        when(outlines.update(project, id, 3, outline)).thenReturn(mock(OutlineResponse.class));
        when(writing.updateManuscript(project, id, 3, manuscript)).thenReturn(mock(ManuscriptResponse.class));
        when(writing.updateReview(project, id, 3, review)).thenReturn(mock(ChapterReviewResponse.class));
        List<String> paths = List.of("/outlines/", "/chapters/1/manuscripts/", "/chapters/1/reviews/");
        List<String> bodies = List.of("{\"title\":\"大纲\",\"arcs\":[]}",
                "{\"title\":\"正文\",\"body\":\"内容\",\"summary\":\"摘要\",\"continuityNotes\":[]}",
                "{\"summary\":\"审阅\",\"issues\":[],\"factProposals\":[]}");
        for (int i = 0; i < paths.size(); i++) {
            mvc.perform(put(base + paths.get(i) + id).header("If-Match", "\"3\"")
                    .contentType(MediaType.APPLICATION_JSON).content("{\"content\":" + bodies.get(i) + "}"))
                    .andExpect(status().isOk()).andExpect(header().string("ETag", "\"0\""));
        }
        verify(outlines).update(project, id, 3, outline);
        verify(writing).updateManuscript(project, id, 3, manuscript);
        verify(writing).updateReview(project, id, 3, review);
    }

    @Test void outlineStillRequiresContentBeforeCallingTheService() throws Exception {
        for (String body : List.of("{}", "{\"content\":null}")) {
            mvc.perform(put(base + "/outlines/" + id).header("If-Match", "0")
                    .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        }
        verifyNoInteractions(outlines, writing);
    }

    @Test void reviewApprovalStillAllowsAnOmittedBodyOrContent() throws Exception {
        when(writing.approveReview(project, id, 3, null)).thenReturn(mock(ChapterReviewResponse.class));
        String path = base + "/chapters/1/reviews/" + id + "/actions/approve";
        mvc.perform(post(path).header("If-Match", "3")).andExpect(status().isOk());
        mvc.perform(post(path).header("If-Match", "3").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verify(writing, org.mockito.Mockito.times(2)).approveReview(project, id, 3, null);
    }
}
