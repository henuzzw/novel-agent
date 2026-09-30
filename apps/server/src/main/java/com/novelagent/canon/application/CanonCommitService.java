package com.novelagent.canon.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.canon.api.CanonCommitResponse;
import com.novelagent.canon.api.CommitCanonRequest;
import com.novelagent.canon.domain.CanonCommit;
import com.novelagent.canon.domain.OutboxEvent;
import com.novelagent.canon.infrastructure.CanonCommitRepository;
import com.novelagent.canon.infrastructure.OutboxEventRepository;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import com.novelagent.writing.domain.ChapterReviewVersion;
import com.novelagent.writing.domain.FactDecision;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.ManuscriptStatus;
import com.novelagent.writing.domain.ManuscriptVersion;
import com.novelagent.writing.domain.ReviewStatus;
import com.novelagent.writing.infrastructure.ChapterReviewVersionRepository;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CanonCommitService {
    private final NovelProjectRepository projects;
    private final ChapterReviewVersionRepository reviews;
    private final ManuscriptVersionRepository manuscripts;
    private final CanonCommitRepository commits;
    private final OutboxEventRepository outbox;
    private final TypedCanonMaterializer typedCanon;
    private final CurrentActorProvider actor;
    private final ObjectMapper mapper;
    private final String topic;

    public CanonCommitService(
            NovelProjectRepository projects,
            ChapterReviewVersionRepository reviews,
            ManuscriptVersionRepository manuscripts,
            CanonCommitRepository commits,
            OutboxEventRepository outbox,
            TypedCanonMaterializer typedCanon,
            CurrentActorProvider actor,
            ObjectMapper mapper,
            @Value("${app.kafka.canon-topic}") String topic) {
        this.projects = projects;
        this.reviews = reviews;
        this.manuscripts = manuscripts;
        this.commits = commits;
        this.outbox = outbox;
        this.typedCanon = typedCanon;
        this.actor = actor;
        this.mapper = mapper;
        this.topic = topic;
    }

    @Transactional
    public CanonCommitResponse commit(UUID projectId, int chapterNumber, CommitCanonRequest request) {
        NovelProject project = requireOwnedProject(projectId);

        CanonCommit existing = commits.findByReviewVersionId(request.reviewVersionId()).orElse(null);
        if (existing != null) {
            return CanonCommitResponse.from(existing);
        }

        ChapterReviewVersion review = requireApprovedReview(projectId, chapterNumber, request.reviewVersionId());
        ManuscriptVersion manuscript = requireAcceptedManuscript(projectId, review.getSourceManuscriptVersionId());
        List<FactProposal> acceptedFacts = acceptedFacts(review);
        long canonVersion = project.commitCanon(request.expectedCanonVersion());

        CanonCommit commit = saveCommit(projectId, chapterNumber, manuscript, review, canonVersion, acceptedFacts);
        typedCanon.materialize(projectId, chapterNumber, commit.getId(), canonVersion, acceptedFacts);
        outbox.save(createCanonCommittedEvent(projectId, chapterNumber, manuscript, commit, canonVersion));
        projects.save(project);
        return CanonCommitResponse.from(commit);
    }

    private NovelProject requireOwnedProject(UUID projectId) {
        return projects.findById(projectId)
                .filter(project -> project.getOwnerId().equals(actor.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private ChapterReviewVersion requireApprovedReview(UUID projectId, int chapterNumber, UUID reviewVersionId) {
        ChapterReviewVersion review = reviews.findByIdAndProjectId(reviewVersionId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("审稿版本不存在"));
        if (review.getChapterNumber() != chapterNumber || review.getStatus() != ReviewStatus.APPROVED) {
            throw new IllegalArgumentException("只有已确认的本章审稿结果可以提交正史");
        }
        return review;
    }

    private ManuscriptVersion requireAcceptedManuscript(UUID projectId, UUID manuscriptVersionId) {
        ManuscriptVersion manuscript = manuscripts.findByIdAndProjectId(manuscriptVersionId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("审稿关联正文不存在"));
        if (manuscript.getStatus() != ManuscriptStatus.AUTHOR_ACCEPTED) {
            throw new IllegalArgumentException("正文尚未由作者确认");
        }
        return manuscript;
    }

    private List<FactProposal> acceptedFacts(ChapterReviewVersion review) {
        return review.getContent().factProposals().stream()
                .filter(fact -> fact.decision() == FactDecision.ACCEPTED)
                .toList();
    }

    private CanonCommit saveCommit(
            UUID projectId,
            int chapterNumber,
            ManuscriptVersion manuscript,
            ChapterReviewVersion review,
            long canonVersion,
            List<FactProposal> acceptedFacts) {
        CanonCommit commit = new CanonCommit(
                UUID.randomUUID(),
                projectId,
                chapterNumber,
                manuscript.getId(),
                review.getId(),
                canonVersion,
                acceptedFacts);
        return commits.saveAndFlush(commit);
    }

    private OutboxEvent createCanonCommittedEvent(
            UUID projectId,
            int chapterNumber,
            ManuscriptVersion manuscript,
            CanonCommit commit,
            long canonVersion) {
        UUID eventId = UUID.randomUUID();
        ObjectNode payload = mapper.createObjectNode();
        payload.put("eventId", eventId.toString());
        payload.put("commitId", commit.getId().toString());
        payload.put("projectId", projectId.toString());
        payload.put("chapterNumber", chapterNumber);
        payload.put("manuscriptVersionId", manuscript.getId().toString());
        payload.put("canonVersion", canonVersion);
        return new OutboxEvent(eventId, projectId, "CanonCommitted", topic, payload);
    }
}
