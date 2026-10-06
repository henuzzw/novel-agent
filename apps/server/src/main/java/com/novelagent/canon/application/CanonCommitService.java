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

/**
 * 正史提交。
 *
 * <p>在同一事务中保存正史提交、物化作者接受的候选事实并写入 Outbox。正文必须由作者确认、审稿必须通过；只提交 ACCEPTED 事实，图谱及向量更新由异步消费者完成。</p>
 */
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

    /**
     * 把作者确认正文和已确认审稿中接受的事实提交正史，校验预期正史版本；同一有效审稿提交可幂等返回。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
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

    /**
     * 显式替换本章有效正史，要求新的确认正文、审稿及匹配的当前提交；后续章已有正史时拒绝直接替换。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
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

    /**
     * 判断指定章节是否存在 active 正史提交，不把确认正文或失效历史提交计为当前正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     */
    @Transactional(readOnly = true)
    public boolean hasCommittedChapter(UUID projectId, int chapterNumber) {
        requireOwnedProject(projectId);
        return commits.existsByProjectIdAndChapterNumberAndActiveTrue(projectId, chapterNumber);
    }

    /**
     * 返回指定章节当前 active 正史提交；尚未提交时返回 null，查询本身不推进版本。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     */
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

    /**
     * 限定项目与章号并要求审稿 APPROVED，不能以其他章或未确认报告提交事实。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param reviewVersionId 作者确认的审稿版本 ID。
     */
    private ChapterReviewVersion requireApprovedReview(UUID projectId, int chapterNumber, UUID reviewVersionId) {
        ChapterReviewVersion review = reviews.findByIdAndProjectId(reviewVersionId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("审稿版本不存在"));
        if (review.getChapterNumber() != chapterNumber || review.getStatus() != ReviewStatus.APPROVED) {
            throw new IllegalArgumentException("只有已确认的本章审稿结果可以提交正史");
        }
        return review;
    }

    /**
     * 要求审稿引用的正文属于本项目且为 AUTHOR_ACCEPTED，不从任意最新草稿抽取正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param manuscriptVersionId 正文版本 ID，作为审稿或正史提交的明确来源。
     */
    private ManuscriptVersion requireAcceptedManuscript(UUID projectId, UUID manuscriptVersionId) {
        ManuscriptVersion manuscript = manuscripts.findByIdAndProjectId(manuscriptVersionId, projectId)
                .orElseThrow(() -> new IllegalArgumentException("审稿关联正文不存在"));
        if (manuscript.getStatus() != ManuscriptStatus.AUTHOR_ACCEPTED) {
            throw new IllegalArgumentException("正文尚未由作者确认");
        }
        return manuscript;
    }

    /**
     * 只筛选作者将 decision 标为 ACCEPTED 的候选事实，未决定和拒绝项不进入正史物化。
     *
     * @param review 已读取的审稿或检查记录，使用前仍需核对状态与来源。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
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

    /**
     * 构造与正史提交同事务保存的 Outbox 事件，仅携带稳定来源标识；Kafka 实际发送由发布器负责。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param manuscript 已读取的正文版本，确认状态与有效正史状态分别判断。
     * @param commit 已保存的正史提交，包含投影来源及版本水位。
     * @param canonVersion 有效正史版本水位，与记录行版本不同。
     */
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
