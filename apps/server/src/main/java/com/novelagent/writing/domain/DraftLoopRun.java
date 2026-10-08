package com.novelagent.writing.domain;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.platform.support.Sha256;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** 单章自动编辑任务。每轮原稿、检查、裁决与新稿均保留；不接受正文或提交正史。 */
@Entity
@Table(name = "draft_loop_run")
public class DraftLoopRun {
    public enum Status { PENDING, RUNNING, STOPPED, FAILED, CANCELLED }
    public enum Phase { A, B, C }
    public enum StopReason { B_CLEAR, C_NO_CHANGE, NEEDS_CONTEXT, NO_PROGRESS, CYCLE_DETECTED, ROUND_LIMIT, SOURCE_CHANGED, CANCELLED, MODEL_ERROR, INTERRUPTED }
    public record Basis(String fingerprint, String prompt, ManuscriptBasis writingBasis) { }
    public record Round(int number, UUID beforeManuscriptId, ManuscriptContent before, DraftCheck check,
            DraftJudgment judgment, UUID afterManuscriptId) { }

    @Id private UUID id;
    private UUID projectId;
    private UUID requestKey;
    private int chapterNumber;
    @Enumerated(EnumType.STRING) private ModelProvider provider;
    private boolean writeFirst;
    private int maxRounds;
    @Enumerated(EnumType.STRING) private Status status;
    @Enumerated(EnumType.STRING) private Phase phase;
    @Enumerated(EnumType.STRING) private StopReason stopReason;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private Basis basis;
    private UUID manuscriptId;
    private long manuscriptRowVersion;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private List<Round> rounds;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private List<String> bodyHashes;
    private String errorMessage;
    private String workerIdentity;
    private Instant createdAt;
    private Instant updatedAt;
    @Version private long rowVersion;

    protected DraftLoopRun() { }

    public static DraftLoopRun create(UUID projectId, UUID requestKey, int chapter, ModelProvider provider,
            boolean writeFirst, int maxRounds, Basis basis, ManuscriptVersion latest) {
        if (chapter < 1 || maxRounds < 1 || maxRounds > 10 || provider == null || provider == ModelProvider.LOCAL_TEMPLATE) {
            throw new IllegalArgumentException("自动编辑请选择真实模型，检查修订上限为1至10轮");
        }
        if (!writeFirst && (latest == null || latest.getStatus() != ManuscriptStatus.DRAFT)) {
            throw new IllegalArgumentException("请先保存本章草稿，或选择从大纲创作");
        }
        var run = new DraftLoopRun();
        run.id = UUID.randomUUID(); run.projectId = projectId; run.requestKey = requestKey;
        run.chapterNumber = chapter; run.provider = provider; run.writeFirst = writeFirst;
        run.maxRounds = maxRounds; run.basis = basis; run.status = Status.PENDING;
        run.phase = writeFirst ? Phase.A : Phase.B;
        run.rounds = new ArrayList<>(); run.bodyHashes = new ArrayList<>();
        run.createdAt = Instant.now(); run.updatedAt = run.createdAt;
        if (latest != null) {
            run.manuscriptId = latest.getId(); run.manuscriptRowVersion = latest.getRowVersion();
        }
        return run;
    }

    public boolean claim() {
        if (status != Status.PENDING) return false;
        status = Status.RUNNING; touch(); return true;
    }
    public void assignWorker(String identity) { workerIdentity = identity; }
    public String getWorkerIdentity() { return workerIdentity; }

    public void wrote(ManuscriptVersion manuscript, String renderedBody) {
        requireRunning();
        manuscriptId = manuscript.getId(); manuscriptRowVersion = manuscript.getRowVersion();
        bodyHashes.add(Sha256.ofUtf8(renderedBody)); phase = Phase.B; touch();
    }

    public void checked(ManuscriptContent before, DraftCheck check) {
        requireRunning();
        if (phase != Phase.B || rounds.size() >= maxRounds) throw new IllegalStateException("无效的检查轮次");
        check.requireEvidenceIn(before.body());
        if (bodyHashes.isEmpty()) bodyHashes.add(Sha256.ofUtf8(before.body()));
        rounds.add(new Round(rounds.size() + 1, manuscriptId, before, check, null, null));
        if (check.issues().isEmpty()) stop(StopReason.B_CLEAR);
        else { phase = Phase.C; touch(); }
    }

    /** 返回是否应保存新稿；重复稿和来回震荡只记裁决，不制造正文版本。 */
    public boolean judged(DraftJudgment judgment) {
        requireRunning();
        if (phase != Phase.C || rounds.isEmpty()) throw new IllegalStateException("请先完成本轮检查");
        var round = rounds.getLast(); judgment.requireCoverage(round.check());
        rounds.set(rounds.size() - 1, new Round(round.number(), round.beforeManuscriptId(), round.before(), round.check(), judgment, null));
        if (judgment.action() != DraftJudgment.Action.REVISED) {
            stop(judgment.action() == DraftJudgment.Action.NEEDS_CONTEXT ? StopReason.NEEDS_CONTEXT : StopReason.C_NO_CHANGE);
            return false;
        }
        String hash = Sha256.ofUtf8(judgment.content().body());
        if (hash.equals(bodyHashes.getLast())) { stop(StopReason.NO_PROGRESS); return false; }
        if (bodyHashes.contains(hash)) { stop(StopReason.CYCLE_DETECTED); return false; }
        return true;
    }

    public void revised(ManuscriptVersion manuscript, String renderedBody) {
        var round = rounds.getLast();
        wrote(manuscript, renderedBody);
        rounds.set(rounds.size() - 1, new Round(round.number(), round.beforeManuscriptId(), round.before(), round.check(), round.judgment(), manuscriptId));
        if (rounds.size() >= maxRounds) stop(StopReason.ROUND_LIMIT);
    }

    public void stop(StopReason reason) {
        if (!active()) return;
        status = reason == StopReason.CANCELLED ? Status.CANCELLED : Status.STOPPED;
        stopReason = reason; touch();
    }

    public void fail(String message) {
        if (!active()) return;
        status = Status.FAILED; stopReason = StopReason.MODEL_ERROR;
        errorMessage = message == null ? "模型调用失败" : message.substring(0, Math.min(1000, message.length())); touch();
    }

    public boolean active() { return status == Status.PENDING || status == Status.RUNNING; }
    private void requireRunning() { if (status != Status.RUNNING) throw new IllegalStateException("自动编辑已停止"); }
    private void touch() { updatedAt = Instant.now(); }
    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getRequestKey() { return requestKey; }
    public int getChapterNumber() { return chapterNumber; }
    public ModelProvider getProvider() { return provider; }
    public boolean isWriteFirst() { return writeFirst; }
    public int getMaxRounds() { return maxRounds; }
    public Status getStatus() { return status; }
    public Phase getPhase() { return phase; }
    public StopReason getStopReason() { return stopReason; }
    public Basis getBasis() { return basis; }
    public UUID getManuscriptId() { return manuscriptId; }
    public long getManuscriptRowVersion() { return manuscriptRowVersion; }
    public List<Round> getRounds() { return List.copyOf(rounds); }
    public String getErrorMessage() { return errorMessage; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
