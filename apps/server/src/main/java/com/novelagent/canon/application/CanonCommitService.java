package com.novelagent.canon.application;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.novelagent.canon.api.CanonCommitResponse;
import com.novelagent.canon.api.CommitCanonRequest;
import com.novelagent.canon.api.ReplaceCanonRequest;
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
            if (existing.isActive() && existing.getProjectId().equals(projectId)
                    && existing.getChapterNumber() == chapterNumber) {
                return CanonCommitResponse.from(existing);
            }
            throw new IllegalStateException("这份审稿对应的正史已失效，不能重复提交");
        }
        if (commits.existsByProjectIdAndChapterNumberAndActiveTrue(projectId, chapterNumber)) {
            throw new IllegalStateException("本章已提交正史，修订稿暂不能再次提交；需使用正史替换流程");
        }

        ChapterReviewVersion review = requireApprovedReview(projectId, chapterNumber, request.reviewVersionId());
        ManuscriptVersion manuscript = requireAcceptedManuscript(projectId, review.getSourceManuscriptVersionId());
        if (manuscript.getChapterNumber() != chapterNumber) {
            throw new IllegalArgumentException("审稿关联的正文不属于本章");
        }
        List<FactProposal> acceptedFacts = acceptedFacts(review);
        long canonVersion = project.commitCanon(request.expectedCanonVersion());

        CanonCommit commit = saveCommit(projectId, chapterNumber, manuscript, review, canonVersion, acceptedFacts);
        typedCanon.materialize(projectId, chapterNumber, commit.getId(), canonVersion, acceptedFacts);
        outbox.save(createCanonCommittedEvent(projectId, chapterNumber, manuscript, commit, canonVersion));
        projects.save(project);
        return CanonCommitResponse.from(commit);
    }

    @Transactional
    public CanonCommitResponse replace(UUID projectId, int chapterNumber, ReplaceCanonRequest request) {
        NovelProject project = requireOwnedProject(projectId);
        CanonCommit existingReviewCommit = commits.findByReviewVersionId(request.reviewVersionId()).orElse(null);
        if (existingReviewCommit != null) {
            if (existingReviewCommit.isActive()
                    && existingReviewCommit.getProjectId().equals(projectId)
                    && existingReviewCommit.getChapterNumber() == chapterNumber) {
                return CanonCommitResponse.from(existingReviewCommit);
            }
            throw new IllegalStateException("这份审稿已用于旧版正史，请重新审稿");
        }
        CanonCommit previous = commits.findByProjectIdAndChapterNumberAndActiveTrue(projectId, chapterNumber)
                .orElseThrow(() -> new IllegalStateException("本章尚无可替换的正史"));
        if (!previous.getId().equals(request.expectedActiveCommitId())) {
            throw new IllegalStateException("本章正史已变化，请刷新后重试");
        }
        if (commits.existsByProjectIdAndChapterNumberGreaterThanAndActiveTrue(projectId, chapterNumber)) {
            throw new IllegalStateException("后续章节已有正史；请先处理后续章节依赖，暂不能替换本章");
        }
        ChapterReviewVersion review = requireApprovedReview(projectId, chapterNumber, request.reviewVersionId());
        ManuscriptVersion manuscript = requireAcceptedManuscript(projectId, review.getSourceManuscriptVersionId());
        if (manuscript.getChapterNumber() != chapterNumber) {
            throw new IllegalArgumentException("审稿关联的正文不属于本章");
        }
        if (previous.getManuscriptVersionId().equals(manuscript.getId())) {
            throw new IllegalStateException("替换正史必须使用重新确认并审稿的新版正文");
        }
        List<FactProposal> acceptedFacts = acceptedFacts(review);
        long canonVersion = project.commitCanon(request.expectedCanonVersion());
        UUID replacementId = UUID.randomUUID();
        previous.supersede(replacementId);
        commits.saveAndFlush(previous);
        CanonCommit replacement = saveCommit(replacementId, projectId, chapterNumber, manuscript, review,
                canonVersion, acceptedFacts);
        typedCanon.retire(previous.getId(), canonVersion);
        typedCanon.materialize(projectId, chapterNumber, replacement.getId(), canonVersion, acceptedFacts);
        outbox.save(createCanonCommittedEvent(projectId, chapterNumber, manuscript, replacement, canonVersion));
        projects.save(project);
        return CanonCommitResponse.from(replacement);
    }

    @Transactional(readOnly = true)
    public boolean hasCommittedChapter(UUID projectId, int chapterNumber) {
        requireOwnedProject(projectId);
        return commits.existsByProjectIdAndChapterNumberAndActiveTrue(projectId, chapterNumber);
    }

    @Transactional(readOnly = true)
    public CanonCommit currentCommit(UUID projectId, int chapterNumber) {
        requireOwnedProject(projectId);
        return commits.findByProjectIdAndChapterNumberAndActiveTrue(projectId, chapterNumber).orElse(null);
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
        return saveCommit(UUID.randomUUID(), projectId, chapterNumber, manuscript, review,
                canonVersion, acceptedFacts);
    }

    private CanonCommit saveCommit(UUID commitId, UUID projectId, int chapterNumber,
            ManuscriptVersion manuscript, ChapterReviewVersion review, long canonVersion,
            List<FactProposal> acceptedFacts) {
        CanonCommit commit = new CanonCommit(
                commitId,
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
