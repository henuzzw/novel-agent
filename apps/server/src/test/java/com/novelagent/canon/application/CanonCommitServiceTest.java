package com.novelagent.canon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.api.CommitCanonRequest;
import com.novelagent.canon.api.ReplaceCanonRequest;
import com.novelagent.canon.domain.CanonCommit;
import com.novelagent.canon.domain.OutboxEvent;
import com.novelagent.canon.infrastructure.CanonCommitRepository;
import com.novelagent.canon.infrastructure.OutboxEventRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.writing.domain.ChapterReviewContent;
import com.novelagent.writing.domain.ChapterReviewVersion;
import com.novelagent.writing.domain.FactDecision;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.infrastructure.ChapterReviewVersionRepository;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class CanonCommitServiceTest {
    private NovelProjectRepository projects;
    private ChapterReviewVersionRepository reviews;
    private ManuscriptVersionRepository manuscripts;
    private CanonCommitRepository commits;
    private OutboxEventRepository outbox;
    private TypedCanonMaterializer typedCanon;
    private CurrentActorProvider actor;
    private CanonCommitService service;

    @BeforeEach
    void setUp() {
        projects = mock(NovelProjectRepository.class);
        reviews = mock(ChapterReviewVersionRepository.class);
        manuscripts = mock(ManuscriptVersionRepository.class);
        commits = mock(CanonCommitRepository.class);
        outbox = mock(OutboxEventRepository.class);
        typedCanon = mock(TypedCanonMaterializer.class);
        actor = mock(CurrentActorProvider.class);
        service = new CanonCommitService(projects, reviews, manuscripts, commits, outbox, typedCanon, actor,
                new ObjectMapper(), "novel.canon.committed.v1");
    }

    @Test
    void commitsOnlyAcceptedFactsAndCreatesOutboxEvent() {
        UUID ownerId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID manuscriptId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        NovelProject project = NovelProject.create(projectId, ownerId, "雾港档案", EntryMode.IDEA);
        ManuscriptVersion manuscript = ManuscriptVersion.create(manuscriptId, projectId, UUID.randomUUID(),
                1, 1, "LOCAL_TEMPLATE", null,
                new ManuscriptContent("第一章", "正文", "摘要", List.of()));
        manuscript.accept();
        ChapterReviewVersion review = ChapterReviewVersion.create(reviewId, projectId, 1, manuscriptId,
                1, "LOCAL_TEMPLATE", null, new ChapterReviewContent("通过", List.of(), List.of(
                        fact("F1", FactDecision.ACCEPTED), fact("F2", FactDecision.REJECTED))));
        review.approve();

        when(actor.currentUserId()).thenReturn(ownerId);
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(commits.findByReviewVersionId(reviewId)).thenReturn(Optional.empty());
        when(reviews.findByIdAndProjectId(reviewId, projectId)).thenReturn(Optional.of(review));
        when(manuscripts.findByIdAndProjectId(manuscriptId, projectId)).thenReturn(Optional.of(manuscript));
        when(commits.saveAndFlush(any(CanonCommit.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.commit(projectId, 1, new CommitCanonRequest(reviewId, 0));

        assertThat(result.canonVersion()).isEqualTo(1);
        assertThat(result.acceptedFacts()).extracting(FactProposal::id).containsExactly("F1");
        assertThat(project.getCurrentCanonVersion()).isEqualTo(1);
        ArgumentCaptor<OutboxEvent> event = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outbox).save(event.capture());
        verify(typedCanon).materialize(projectId, 1, result.id(), 1,
                List.of(fact("F1", FactDecision.ACCEPTED)));
        assertThat(event.getValue().getPayload().path("commitId").asText()).isEqualTo(result.id().toString());
        assertThat(event.getValue().getPayload().path("canonVersion").asLong()).isEqualTo(1);
    }

    @Test
    void returnsExistingCommitWithoutWritingAgain() {
        UUID ownerId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        NovelProject project = NovelProject.create(projectId, ownerId, "雾港档案", EntryMode.IDEA);
        CanonCommit existing = new CanonCommit(UUID.randomUUID(), projectId, 1, UUID.randomUUID(),
                reviewId, 1, List.of());
        when(actor.currentUserId()).thenReturn(ownerId);
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(commits.findByReviewVersionId(reviewId)).thenReturn(Optional.of(existing));

        var result = service.commit(projectId, 1, new CommitCanonRequest(reviewId, 0));

        assertThat(result.id()).isEqualTo(existing.getId());
        verify(outbox, never()).save(any());
        verify(typedCanon, never()).materialize(any(), any(Integer.class), any(), any(Long.class), any());
        verify(projects, never()).save(any());
    }

    @Test
    void rejectsASecondCommitForTheSameChapter() {
        UUID ownerId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        NovelProject project = NovelProject.create(projectId, ownerId, "雾港档案", EntryMode.IDEA);
        when(actor.currentUserId()).thenReturn(ownerId);
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(commits.findByReviewVersionId(reviewId)).thenReturn(Optional.empty());
        when(commits.existsByProjectIdAndChapterNumberAndActiveTrue(projectId, 1)).thenReturn(true);

        assertThat(service.hasCommittedChapter(projectId, 1)).isTrue();
        assertThatThrownBy(() -> service.commit(projectId, 1, new CommitCanonRequest(reviewId, 0)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("已提交正史");
        verify(outbox, never()).save(any());
        verify(commits, never()).saveAndFlush(any());
    }

    @Test
    void replacesActiveCanonAndRetiresOldFacts() {
        UUID ownerId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        UUID manuscriptId = UUID.randomUUID();
        CanonCommit previous = new CanonCommit(UUID.randomUUID(), projectId, 1, UUID.randomUUID(),
                UUID.randomUUID(), 1, List.of());
        NovelProject project = NovelProject.create(projectId, ownerId, "雾港档案", EntryMode.IDEA);
        project.commitCanon(0);
        ManuscriptVersion manuscript = ManuscriptVersion.create(manuscriptId, projectId, UUID.randomUUID(),
                1, 2, "LOCAL_TEMPLATE", null, new ManuscriptContent("第一章", "新版正文", "摘要", List.of()));
        manuscript.accept();
        ChapterReviewVersion review = ChapterReviewVersion.create(reviewId, projectId, 1, manuscriptId,
                2, "LOCAL_TEMPLATE", null, new ChapterReviewContent("通过", List.of(), List.of()));
        review.approve();
        when(actor.currentUserId()).thenReturn(ownerId);
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(commits.findByProjectIdAndChapterNumberAndActiveTrue(projectId, 1)).thenReturn(Optional.of(previous));
        when(reviews.findByIdAndProjectId(reviewId, projectId)).thenReturn(Optional.of(review));
        when(manuscripts.findByIdAndProjectId(manuscriptId, projectId)).thenReturn(Optional.of(manuscript));
        when(commits.saveAndFlush(any(CanonCommit.class))).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.replace(projectId, 1, new ReplaceCanonRequest(reviewId, previous.getId(), 1));

        assertThat(result.canonVersion()).isEqualTo(2);
        assertThat(previous.isActive()).isFalse();
        assertThat(previous.getSupersededByCommitId()).isEqualTo(result.id());
        verify(typedCanon).retire(previous.getId(), 2);
        verify(typedCanon).materialize(projectId, 1, result.id(), 2, List.of());
        verify(outbox).save(any(OutboxEvent.class));
    }

    @Test
    void refusesReplacementWhenLaterCanonDependsOnChapter() {
        UUID ownerId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID reviewId = UUID.randomUUID();
        CanonCommit previous = new CanonCommit(UUID.randomUUID(), projectId, 1, UUID.randomUUID(),
                UUID.randomUUID(), 1, List.of());
        NovelProject project = NovelProject.create(projectId, ownerId, "雾港档案", EntryMode.IDEA);
        when(actor.currentUserId()).thenReturn(ownerId);
        when(projects.findById(projectId)).thenReturn(Optional.of(project));
        when(commits.findByProjectIdAndChapterNumberAndActiveTrue(projectId, 1)).thenReturn(Optional.of(previous));
        when(commits.existsByProjectIdAndChapterNumberGreaterThanAndActiveTrue(projectId, 1)).thenReturn(true);

        assertThatThrownBy(() -> service.replace(projectId, 1,
                new ReplaceCanonRequest(reviewId, previous.getId(), 0)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("后续章节");
        verify(outbox, never()).save(any());
    }

    private FactProposal fact(String id, FactDecision decision) {
        return new FactProposal(id, "EVENT", "顾弦", "发现", "旧笔记", "正文证据", decision);
    }
}
