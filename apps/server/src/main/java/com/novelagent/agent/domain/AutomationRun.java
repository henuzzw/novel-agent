package com.novelagent.agent.domain;

import com.novelagent.planning.application.ModelProvider;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "automation_run")
public class AutomationRun {
    @Id private UUID id;
    @Column(name = "project_id", nullable = false) private UUID projectId;
    @Column(name = "outline_id", nullable = false) private UUID outlineId;
    @Column(name = "request_key", nullable = false) private UUID requestKey;
    @Column(name = "first_chapter", nullable = false) private int firstChapter;
    @Column(name = "last_chapter", nullable = false) private int lastChapter;
    @Column(name = "current_chapter", nullable = false) private int currentChapter;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private ModelProvider provider;
    @Column(columnDefinition = "text") private String instruction;
    @Column(name = "quality_review_enabled", nullable = false) private boolean qualityReviewEnabled;
    @Column(name = "max_auto_revision_rounds", nullable = false) private int maxAutoRevisionRounds;
    @Column(name = "max_generation_steps", nullable = false) private int maxGenerationSteps;
    @Enumerated(EnumType.STRING) @Column(nullable = false, length = 32) private AutomationStatus status;
    @Column(name = "cancel_requested", nullable = false) private boolean cancelRequested;
    @Column(nullable = false) private int attempt;
    @Column(name = "waiting_reason", columnDefinition = "text") private String waitingReason;
    @Column(name = "error_code", length = 100) private String errorCode;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb")
    private List<AutomationStep> steps = new ArrayList<>();
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "updated_at", nullable = false) private Instant updatedAt;
    @Version @Column(name = "row_version", nullable = false) private long rowVersion;

    protected AutomationRun() { }

    public static AutomationRun create(UUID projectId, UUID outlineId, int firstChapter, int lastChapter,
            ModelProvider provider, String instruction) {
        return create(projectId, outlineId, UUID.randomUUID(), firstChapter, lastChapter, provider, instruction);
    }

    public static AutomationRun create(UUID projectId, UUID outlineId, UUID requestKey,
            int firstChapter, int lastChapter, ModelProvider provider, String instruction) {
        return create(projectId, outlineId, requestKey, firstChapter, lastChapter, provider, instruction, false);
    }

    public static AutomationRun create(UUID projectId, UUID outlineId, UUID requestKey,
            int firstChapter, int lastChapter, ModelProvider provider, String instruction, boolean qualityReviewEnabled) {
        return create(projectId, outlineId, requestKey, firstChapter, lastChapter, provider, instruction,
                qualityReviewEnabled, 0, 100);
    }

    public static AutomationRun create(UUID projectId, UUID outlineId, UUID requestKey,
            int firstChapter, int lastChapter, ModelProvider provider, String instruction, boolean qualityReviewEnabled,
            int maxAutoRevisionRounds, int maxGenerationSteps) {
        if (firstChapter < 1 || lastChapter < firstChapter || lastChapter - firstChapter >= 20) {
            throw new IllegalArgumentException("章节范围必须递增，且一次最多 20 章");
        }
        if (maxAutoRevisionRounds < 0 || maxAutoRevisionRounds > 3) {
            throw new IllegalArgumentException("每章自动润色轮数必须在 0 至 3 之间");
        }
        if (maxGenerationSteps < 1 || maxGenerationSteps > 500) {
            throw new IllegalArgumentException("任务生成次数上限必须在 1 至 500 之间");
        }
        if (maxAutoRevisionRounds > 0 && (!qualityReviewEnabled || provider == null || provider == ModelProvider.LOCAL_TEMPLATE)) {
            throw new IllegalArgumentException("自动语句润色需要开启质量检查并选择真实模型");
        }
        AutomationRun run = new AutomationRun();
        run.id = UUID.randomUUID();
        run.projectId = projectId;
        run.outlineId = outlineId;
        run.requestKey = requestKey;
        run.firstChapter = firstChapter;
        run.lastChapter = lastChapter;
        run.currentChapter = firstChapter;
        run.provider = provider;
        run.instruction = instruction;
        run.qualityReviewEnabled = qualityReviewEnabled;
        run.maxAutoRevisionRounds = maxAutoRevisionRounds;
        run.maxGenerationSteps = maxGenerationSteps;
        run.status = AutomationStatus.PENDING;
        run.createdAt = Instant.now();
        run.updatedAt = run.createdAt;
        return run;
    }

    public void start(Instant now) {
        if (status == AutomationStatus.CANCELLED || status == AutomationStatus.SUCCEEDED) {
            throw new IllegalStateException("已结束任务不能恢复");
        }
        if (status == AutomationStatus.RUNNING && updatedAt.plus(Duration.ofMinutes(20)).isAfter(now)) {
            throw new IllegalStateException("任务仍在运行");
        }
        interruptStep(now);
        status = AutomationStatus.RUNNING;
        attempt++;
        waitingReason = null;
        errorCode = null;
        updatedAt = now;
    }

    public void cancel() {
        if (status == AutomationStatus.SUCCEEDED || status == AutomationStatus.CANCELLED) {
            return;
        }
        if (cancelRequested && updatedAt.plus(Duration.ofMinutes(20)).isAfter(Instant.now())) return;
        cancelRequested = true;
        if (status != AutomationStatus.RUNNING || updatedAt.plus(Duration.ofMinutes(20)).isBefore(Instant.now())) {
            interruptStep(Instant.now());
            status = AutomationStatus.CANCELLED;
        }
        updatedAt = Instant.now();
    }

    public boolean checkpoint() {
        if (cancelRequested) {
            status = AutomationStatus.CANCELLED;
            updatedAt = Instant.now();
            return false;
        }
        return status == AutomationStatus.RUNNING;
    }

    public void beginStep(String stage) {
        requireRunning();
        if (!checkpoint()) return;
        if (getUsedGenerationSteps() >= maxGenerationSteps) {
            waitForUser("已达到任务生成次数上限（" + maxGenerationSteps + " 次）；请人工处理或取消后另建任务，继续不会重置额度");
            return;
        }
        if ("QUALITY_REVISION".equals(stage) && getUsedAutoRevisionRounds() >= maxAutoRevisionRounds) {
            waitForUser("已达到本章自动润色轮数上限，请由作者处理剩余建议并确认正文");
            return;
        }
        if ("QUALITY_REVISION".equals(stage) && maxGenerationSteps - getUsedGenerationSteps() < 2) {
            waitForUser("任务剩余生成额度不足以完成润色和复检，请由作者处理或取消后另建任务");
            return;
        }
        Instant now = Instant.now();
        steps.add(new AutomationStep(currentChapter, stage, "RUNNING", null, now, null, null));
        updatedAt = now;
    }

    public void completeStep(UUID artifactId) {
        requireRunning();
        AutomationStep step = steps.getLast();
        Instant now = Instant.now();
        steps.set(steps.size() - 1, new AutomationStep(step.chapterNumber(), step.stage(), "SUCCEEDED",
                artifactId, step.startedAt(), now, null));
        updatedAt = now;
    }

    public void waitForUser(String reason) {
        requireRunning();
        if (!checkpoint()) return;
        status = AutomationStatus.WAITING_FOR_USER;
        waitingReason = reason;
        updatedAt = Instant.now();
    }

    public void advanceChapter() {
        requireRunning();
        if (!checkpoint()) return;
        if (currentChapter == lastChapter) status = AutomationStatus.SUCCEEDED;
        else currentChapter++;
        updatedAt = Instant.now();
    }

    public void fail(String code) {
        requireRunning();
        if (!steps.isEmpty() && steps.getLast().status().equals("RUNNING")) {
            AutomationStep step = steps.getLast();
            steps.set(steps.size() - 1, new AutomationStep(step.chapterNumber(), step.stage(), "FAILED",
                    null, step.startedAt(), Instant.now(), code));
        }
        errorCode = code;
        status = cancelRequested ? AutomationStatus.CANCELLED : AutomationStatus.FAILED;
        updatedAt = Instant.now();
    }

    private void interruptStep(Instant now) {
        if (!steps.isEmpty() && steps.getLast().status().equals("RUNNING")) {
            AutomationStep step = steps.getLast();
            steps.set(steps.size() - 1, new AutomationStep(step.chapterNumber(), step.stage(), "FAILED",
                    null, step.startedAt(), now, "STEP_INTERRUPTED"));
        }
    }

    private void requireRunning() {
        if (status != AutomationStatus.RUNNING) throw new IllegalStateException("任务未运行");
    }

    public UUID getId() { return id; }
    public UUID getProjectId() { return projectId; }
    public UUID getOutlineId() { return outlineId; }
    public UUID getRequestKey() { return requestKey; }
    public int getFirstChapter() { return firstChapter; }
    public int getLastChapter() { return lastChapter; }
    public int getCurrentChapter() { return currentChapter; }
    public ModelProvider getProvider() { return provider; }
    public String getInstruction() { return instruction; }
    public boolean isQualityReviewEnabled() { return qualityReviewEnabled; }
    public int getMaxAutoRevisionRounds() { return maxAutoRevisionRounds; }
    public int getMaxGenerationSteps() { return maxGenerationSteps; }
    public int getUsedGenerationSteps() { return steps.size(); }
    public int getUsedAutoRevisionRounds() {
        return (int) steps.stream().filter(step -> step.chapterNumber() == currentChapter
                && "QUALITY_REVISION".equals(step.stage())).count();
    }
    public AutomationStatus getStatus() { return status; }
    public boolean isCancelRequested() { return cancelRequested; }
    public int getAttempt() { return attempt; }
    public String getWaitingReason() { return waitingReason; }
    public String getErrorCode() { return errorCode; }
    public List<AutomationStep> getSteps() { return List.copyOf(steps); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
