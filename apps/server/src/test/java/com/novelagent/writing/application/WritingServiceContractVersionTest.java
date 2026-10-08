package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import com.novelagent.writing.api.GenerateWritingRequest;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.infrastructure.ChapterContractVersionRepository;
import com.novelagent.writing.infrastructure.ChapterContractReviewVersionRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class WritingServiceContractVersionTest {
    @Test void retiredWritesNeverCallModelsOrRepositories() {
        var contexts = mock(WritingContextService.class);
        var contracts = mock(ChapterContractVersionRepository.class);
        var reviews = mock(ChapterContractReviewVersionRepository.class);
        var workflow = mock(WritingGenerationWorkflow.class);
        var service = new ChapterContractService(contexts, contracts, reviews, null);
        var project = UUID.randomUUID();
        var id = UUID.randomUUID();
        var request = new GenerateWritingRequest(ModelProvider.DEEPSEEK, null, null, null, null);
        java.util.List<Runnable> operations = java.util.List.of(
                () -> service.generateContract(project, 1, request),
                () -> service.generateContractReview(project, 1, request),
                () -> service.updateContract(project, id, 0, null),
                () -> service.approveContract(project, id, 0),
                () -> service.approveContractReview(project, id, 0, null));
        for (var operation : operations) {
            assertThatThrownBy(operation::run).isInstanceOfSatisfying(ResponseStatusException.class,
                    failure -> org.assertj.core.api.Assertions.assertThat(failure.getStatusCode().value()).isEqualTo(410));
        }
        verifyNoInteractions(contracts, reviews, workflow);
    }
}
