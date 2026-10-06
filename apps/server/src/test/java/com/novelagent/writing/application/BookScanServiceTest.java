package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.writing.domain.ReaderExperienceMemory;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BookScanServiceTest {
    private final ProjectAccessService access = mock(ProjectAccessService.class);
    private final OutlineVersionRepository outlines = mock(OutlineVersionRepository.class);
    private final ReaderExperienceService readers = mock(ReaderExperienceService.class);
    private final BookScanService service = new BookScanService(access, outlines, readers, new ObjectMapper());

    @Test void twentyChapterCoverageIsNotClaimedAsFullBodyLiteraryReview() {
        var id = UUID.randomUUID();
        var plans = IntStream.rangeClosed(1, 20).mapToObj(number -> new ChapterPlan(number, "章" + number,
                "主角", "目标", "同一计划文字", "线索", "后果", 1000, 2000)).toList();
        var memory = new ReaderExperienceMemory("reader-experience-memory/1", "EXISTING_CANON_SUMMARIES", id, 0L, List.of(), List.of());
        var result = service.assemble(id, 0, 0, plans, memory, List.of());
        assertThat(result.mode()).isEqualTo("RULES_SUMMARY_ONLY");
        assertThat(result.notice()).contains("未读取全书完整正文", "未评估文学质量");
        assertThat(result.chapters()).hasSize(20);
        assertThat(result.observations().stream().filter(value -> value.kind().equals("MISSING_CANON"))).hasSize(20);
        assertThat(result.observations().stream().filter(value -> value.kind().equals("IDENTICAL_PLAN_TEXT"))).hasSize(19);
        assertThat(service.assemble(id, 1, 0, plans, memory, List.of()).fingerprint()).isNotEqualTo(result.fingerprint());
    }

    @Test void authorPermissionIsCheckedBeforeAnyOtherRead() {
        var id = UUID.randomUUID();
        when(access.requireOwnedProject(id)).thenThrow(new ProjectNotFoundException(id));
        assertThatThrownBy(() -> service.scan(id)).isInstanceOf(ProjectNotFoundException.class);
        verifyNoInteractions(outlines, readers);
    }
}
