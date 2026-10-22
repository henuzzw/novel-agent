package com.novelagent.canon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.domain.CanonCommit;
import com.novelagent.canon.infrastructure.CanonCommitRepository;
import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.writing.domain.ChapterReviewContent;
import com.novelagent.writing.domain.FactDecision;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import com.novelagent.writing.infrastructure.WritingGenerationGateway;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class PublishedMemoryServiceTest {
    private final UUID projectId = UUID.randomUUID(), commitId = UUID.randomUUID();
    private final CanonCommit commit = new CanonCommit(commitId, projectId, 1, UUID.randomUUID(), null, 1, List.of());
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final WritingGenerationGateway gateway = mock(WritingGenerationGateway.class);
    private final TypedCanonMaterializer materializer = mock(TypedCanonMaterializer.class);
    private final CanonPublicationEvents events = mock(CanonPublicationEvents.class);
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void automaticallySavesTheWholeValidatedExtractionWithoutConfirmation() throws Exception {
        var event = PublishedMemoryPolicyTest.fact("EVENT_CREATE", "归还纸条", "归还", null, "江澈把纸条还给沈秋。");
        var relation = PublishedMemoryPolicyTest.fact("RELATION_CHANGE", "江澈", "沈秋", null, event.evidence());
        var ambiguous = PublishedMemoryPolicyTest.fact("KNOWLEDGE_CHANGE", "沈秋", "纸条内容", null, "沈秋已经读过纸条。");
        var source = source(event.evidence() + ambiguous.evidence());
        var service = service();
        when(gateway.publishedMemory(any(), any(), any(), any(), anyString())).thenReturn(new ChapterReviewContent("记忆", List.of(), List.of(event, relation, ambiguous)));
        service.process(source);
        var accepted = ArgumentCaptor.forClass(List.class);
        verify(materializer).materialize(eq(projectId), eq(1), eq(commitId), eq(1L), accepted.capture());
        assertThat(accepted.getValue()).hasSize(3);
        assertThat(commit.getAcceptedFacts()).extracting(FactProposal::decision).containsOnly(FactDecision.ACCEPTED);
        verify(events).emit(commit, 1L);
        var args = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(anyString(), args.capture());
        assertThat(args.getValue()[0]).isEqualTo("SUCCEEDED");
        var candidates = mapper.readTree((String) args.getValue()[1]);
        assertThat(candidates.get(0).get("decision").asText()).isEqualTo("ACCEPTED");
        assertThat(candidates.get(2).get("decision").asText()).isEqualTo("ACCEPTED");
    }

    @Test
    void completesFullyAcceptedMemoryWithoutAuthorConfirmation() {
        var event = PublishedMemoryPolicyTest.fact("EVENT_CREATE", "归还纸条", "归还", null, "江澈把纸条还给沈秋。");
        var service = service();
        when(gateway.publishedMemory(any(), any(), any(), any(), anyString())).thenReturn(new ChapterReviewContent("记忆", List.of(), List.of(event)));
        service.process(source(event.evidence()));
        verify(jdbc).update(anyString(), eq("SUCCEEDED"), anyString(), eq(commitId));
        assertThat(commit.getAcceptedFacts()).hasSize(1);
    }

    @Test
    void rejectsMissingEvidenceAndNeverIndexesAReplacedPublication() {
        var event = PublishedMemoryPolicyTest.fact("EVENT_CREATE", "归还纸条", "归还", null, "江澈把纸条还给沈秋。");
        var service = service();
        when(gateway.publishedMemory(any(), any(), any(), any(), anyString())).thenReturn(new ChapterReviewContent("记忆", List.of(), List.of(event)));
        service.process(source("没有对应证据"));
        verifyNoInteractions(materializer, events);
        commit.supersede(UUID.randomUUID());
        service.process(source(event.evidence()));
        verifyNoInteractions(materializer, events);
        verify(jdbc).update(anyString(), eq("SUPERSEDED"), eq("[]"), eq(commitId));
    }

    @Test void rejectsTheWholeBatchWhenAnEntityCannotBeResolved() {
        var valid = PublishedMemoryPolicyTest.fact("EVENT_CREATE", "归还纸条", "归还", null, "江澈把纸条还给沈秋。");
        var unresolved = PublishedMemoryPolicyTest.fact("KNOWLEDGE_CHANGE", "她", "纸条内容", null, "她已经读过纸条。");
        when(gateway.publishedMemory(any(), any(), any(), any(), anyString())).thenReturn(new ChapterReviewContent("记忆", List.of(), List.of(valid, unresolved)));
        service().process(source(valid.evidence() + unresolved.evidence()));
        verifyNoInteractions(materializer, events);
        verify(jdbc).update(anyString(), contains("整批未落库"), anyString(), anyString(), eq(commitId));
        assertThat(commit.getAcceptedFacts()).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private PublishedMemoryService service() {
        var access = mock(ProjectAccessService.class);
        when(access.requireOwnedProject(projectId)).thenReturn(mock(NovelProject.class));
        var commits = mock(CanonCommitRepository.class);
        when(commits.findById(commitId)).thenReturn(Optional.of(commit));
        var catalog = mock(EntityCatalogService.class);
        when(catalog.forPublishedMemory(projectId)).thenReturn(EntityCatalogContext.empty());
        when(jdbc.queryForObject(anyString(), eq(String.class), eq(commitId))).thenReturn("RUNNING");
        var transactions = mock(TransactionTemplate.class);
        doAnswer(call -> { ((Consumer<TransactionStatus>) call.getArgument(0)).accept(mock(TransactionStatus.class)); return null; })
                .when(transactions).executeWithoutResult(any());
        return new PublishedMemoryService(jdbc, mapper, access, commits, mock(ManuscriptVersionRepository.class),
                mock(CharacterNameService.class), catalog, gateway, materializer, events, mock(EntityManager.class), transactions);
    }
    private PublishedMemoryService.Source source(String body) {
        return new PublishedMemoryService.Source(commitId, projectId, ModelProvider.LOCAL_CODEX,
                new ManuscriptContent("纸条", body, "归还", List.of()), EntityCatalogContext.empty());
    }
}
