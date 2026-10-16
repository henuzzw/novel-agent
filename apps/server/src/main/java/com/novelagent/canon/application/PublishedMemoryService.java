package com.novelagent.canon.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.infrastructure.CanonCommitRepository;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.domain.FactDecision;
import com.novelagent.writing.domain.FactProposal;
import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import com.novelagent.writing.infrastructure.WritingGenerationGateway;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/** A rebuildable index of the published source, never a publication gate. */
@Service
public class PublishedMemoryService {
    public record View(UUID commitId, UUID manuscriptVersionId, String status, List<FactProposal> candidates,
            String error, long version) { }
    public record Confirmation(long version, List<FactProposal> candidates) { }
    public record History(UUID id, long version, String action, List<FactProposal> candidates, Instant createdAt) { }
    record Source(UUID commitId, UUID projectId, ModelProvider provider, ManuscriptContent manuscript,
            EntityCatalogContext catalog, String identityContext) {
        Source(UUID commitId, UUID projectId, ModelProvider provider, ManuscriptContent manuscript, EntityCatalogContext catalog) {
            this(commitId, projectId, provider, manuscript, catalog, "");
        }
    }
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ProjectAccessService access;
    private final CanonCommitRepository commits;
    private final ManuscriptVersionRepository manuscripts;
    private final CharacterNameService names;
    private final EntityCatalogService catalog;
    private final WritingGenerationGateway gateway;
    private final TypedCanonMaterializer materializer;
    private final CanonPublicationEvents events;
    private final EntityManager entities;
    private final TransactionTemplate transactions;

    public PublishedMemoryService(JdbcTemplate jdbc, ObjectMapper mapper, ProjectAccessService access,
            CanonCommitRepository commits, ManuscriptVersionRepository manuscripts, CharacterNameService names,
            EntityCatalogService catalog, WritingGenerationGateway gateway, TypedCanonMaterializer materializer,
            CanonPublicationEvents events, EntityManager entities,
            @Qualifier("writingTransactionTemplate") TransactionTemplate transactions) {
        this.jdbc = jdbc; this.mapper = mapper; this.access = access; this.commits = commits;
        this.manuscripts = manuscripts; this.names = names; this.catalog = catalog; this.gateway = gateway;
        this.materializer = materializer; this.events = events; this.entities = entities; this.transactions = transactions;
    }

    public View status(UUID projectId, int chapter) {
        return transactions.execute(tx -> {
            access.requireOwnedProject(projectId);
            var commit = commits.findByProjectIdAndChapterNumberAndActiveTrue(projectId, chapter).orElse(null);
            if (commit == null) return new View(null, null, "NOT_PUBLISHED", List.of(), null, 0);
            var views = jdbc.query("SELECT status, candidates, error_message, row_version FROM canon_memory_job WHERE commit_id = ?",
                    (rs, row) -> new View(commit.getId(), commit.getManuscriptVersionId(), rs.getString(1),
                            read(rs.getString(2)), rs.getString(3), rs.getLong(4)), commit.getId());
            return views.isEmpty() ? new View(commit.getId(), commit.getManuscriptVersionId(), "LEGACY", List.of(), null, 0) : views.getFirst();
        });
    }

    public void recoverInterrupted() {
        jdbc.update("UPDATE canon_memory_job SET status='FAILED', error_message='服务重启中断了记忆整理，可手动重试', "
                + "row_version=row_version+1, updated_at=now() WHERE status='RUNNING'");
    }

    /** Claim before dispatch; no transaction or scheduler thread is held during model generation. */
    Source claim() {
        return transactions.execute(tx -> {
            jdbc.update("UPDATE canon_memory_job j SET status='SUPERSEDED', row_version=row_version+1, updated_at=now() "
                    + "FROM canon_commit c WHERE j.commit_id=c.id AND NOT c.active AND j.status IN ('PENDING','RUNNING')");
            var ids = jdbc.query("""
                    SELECT j.commit_id FROM canon_memory_job j JOIN canon_commit c ON c.id=j.commit_id
                    WHERE j.status='PENDING' AND c.active AND NOT EXISTS (
                      SELECT 1 FROM canon_memory_job older JOIN canon_commit oc ON oc.id=older.commit_id
                      WHERE older.project_id=j.project_id AND oc.active AND oc.canon_version<c.canon_version
                        AND older.status NOT IN ('SUCCEEDED','SKIPPED'))
                    ORDER BY j.created_at LIMIT 1 FOR UPDATE OF j SKIP LOCKED
                    """, (rs, row) -> rs.getObject(1, UUID.class));
            if (ids.isEmpty()) return null;
            var commit = commits.findById(ids.getFirst()).orElseThrow();
            access.requireOwnedProject(commit.getProjectId());
            var manuscript = manuscripts.findByIdAndProjectId(commit.getManuscriptVersionId(), commit.getProjectId()).orElseThrow();
            var rendered = names.render(commit.getProjectId(), manuscript.getContent(), ManuscriptContent.class);
            String provider = jdbc.queryForObject("SELECT provider FROM canon_memory_job WHERE commit_id=?", String.class, commit.getId());
            jdbc.update("UPDATE canon_memory_job SET status='RUNNING', source_body=?, error_message=NULL, "
                    + "row_version=row_version+1, updated_at=now() WHERE commit_id=?", rendered.body(), commit.getId());
            return new Source(commit.getId(), commit.getProjectId(), ModelProvider.valueOf(provider), rendered,
                    catalog.forPublishedMemory(commit.getProjectId()), manuscript.getWritingBasis() == null ? ""
                        : names.render(commit.getProjectId(), manuscript.getWritingBasis().plan().pov() + "\n"
                            + String.join("\n", manuscript.getWritingBasis().plan().requiredBeats())));
        });
    }

    void process(Source source) {
        var extracted = new java.util.concurrent.atomic.AtomicReference<List<FactProposal>>();
        try {
            var result = gateway.publishedMemory(source.projectId(), source.manuscript(), source.catalog(), source.provider(), source.identityContext());
            PublishedMemoryPolicy.validate(result.factProposals(), source.manuscript().body());
            extracted.set(result.factProposals().stream().map(f -> PublishedMemoryPolicy.decide(f, FactDecision.PENDING)).toList());
            transactions.executeWithoutResult(tx -> {
                lockProject(source.projectId());
                var commit = commits.findById(source.commitId()).orElseThrow();
                entities.refresh(commit, LockModeType.PESSIMISTIC_WRITE);
                if (!commit.isActive()) {
                    finish(source.commitId(), "SUPERSEDED", List.of());
                    return;
                }
                String state = jdbc.queryForObject("SELECT status FROM canon_memory_job WHERE commit_id=? FOR UPDATE", String.class, source.commitId());
                if (!"RUNNING".equals(state)) return;
                var currentCatalog = catalog.forPublishedMemory(source.projectId());
                var candidates = result.factProposals().stream().map(f -> {
                    if (!PublishedMemoryPolicy.autoAcceptable(f, source.catalog(), currentCatalog))
                        throw new IllegalArgumentException("记忆实体引用无效，整批未落库：" + f.id());
                    return PublishedMemoryPolicy.decide(f, FactDecision.ACCEPTED);
                }).toList();
                var view = lockedView(commit.getId());
                long indexVersion = commit.getCanonVersion();
                if (!commit.getAcceptedFacts().isEmpty()) {
                    recordHistory(source.projectId(), commit.getId(), view, "REGENERATE");
                    var project = access.requireOwnedProject(source.projectId());
                    indexVersion = project.commitCanon(project.getCurrentCanonVersion());
                    materializer.retire(commit.getId(), indexVersion);
                }
                materializer.materialize(source.projectId(), commit.getChapterNumber(), commit.getId(), indexVersion, candidates);
                commit.replaceMemoryFacts(candidates);
                commits.saveAndFlush(commit);
                events.emit(commit, indexVersion);
                finish(commit.getId(), "SUCCEEDED", candidates);
            });
        } catch (Exception failure) {
            String reason = failure instanceof IllegalArgumentException
                    ? "记忆输出或实体引用校验失败，整批未落库；可重新整理或编辑记忆后保存"
                    : "记忆整理或落库失败；自动推进已暂停，可重试";
            if (failure instanceof IllegalArgumentException && failure.getMessage() != null
                    && failure.getMessage().startsWith("记忆实体引用无效，整批未落库：")) reason = failure.getMessage();
            String candidates = extracted.get() == null ? null : write(extracted.get());
            jdbc.update("UPDATE canon_memory_job SET status='FAILED', error_message=?, "
                    + "candidates=CASE WHEN CAST(? AS text) IS NULL THEN candidates ELSE CAST(? AS jsonb) END, "
                    + "row_version=row_version+1, updated_at=now() WHERE commit_id=? AND status='RUNNING'", reason, candidates, candidates, source.commitId());
        }
    }

    public View retry(UUID projectId, int chapter, UUID commitId, long version) {
        transactions.executeWithoutResult(tx -> {
            requireCurrent(projectId, chapter, commitId);
            var view = lockedView(commitId);
            check(view, version);
            if (!List.of("FAILED", "SKIPPED", "NEEDS_CONFIRMATION").contains(view.status())) throw new IllegalStateException("只有失败、待处理或未执行的记忆整理可以启动");
            jdbc.update("UPDATE canon_memory_job SET status='PENDING', error_message=NULL, row_version=row_version+1, updated_at=now() WHERE commit_id=?", commitId);
        });
        return status(projectId, chapter);
    }

    public View confirm(UUID projectId, int chapter, UUID commitId, Confirmation request) {
        transactions.executeWithoutResult(tx -> {
            var commit = requireCurrent(projectId, chapter, commitId);
            var view = lockedView(commitId);
            check(view, request.version());
            if (!"NEEDS_CONFIRMATION".equals(view.status())) throw new IllegalStateException("没有待核对的记忆条目");
            String body = jdbc.queryForObject("SELECT source_body FROM canon_memory_job WHERE commit_id=?", String.class, commitId);
            PublishedMemoryPolicy.validate(request.candidates(), body);
            if (request.candidates().size() != view.candidates().size()) throw new IllegalArgumentException("请保留全部记忆条目");
            var accepted = new java.util.ArrayList<FactProposal>();
            for (var original : view.candidates()) {
                var supplied = request.candidates().stream().filter(f -> f.id().equals(original.id())).findFirst().orElseThrow();
                if (original.decision() != FactDecision.PENDING && !original.equals(supplied)) {
                    throw new IllegalArgumentException("已整理的记忆条目不能覆盖");
                }
                // Author may resolve entity IDs, but cannot rewrite the extracted claim or its evidence.
                if (!sameClaim(original, supplied) || supplied.decision() == null) throw new IllegalArgumentException("只能核对决定和实体引用，不能改写正文证据");
                if (original.decision() == FactDecision.PENDING && supplied.decision() == FactDecision.ACCEPTED) accepted.add(supplied);
            }
            if (!accepted.isEmpty()) {
                materializer.materialize(projectId, chapter, commitId, commit.getCanonVersion(), accepted);
                commit.addMemoryFacts(accepted);
                commits.saveAndFlush(commit);
                events.emit(commit);
            }
            finish(commitId, request.candidates().stream().anyMatch(f -> f.decision() == FactDecision.PENDING)
                    ? "NEEDS_CONFIRMATION" : "SUCCEEDED", request.candidates());
        });
        return status(projectId, chapter);
    }

    /** Author edits use the same source evidence and entity checks, with optimistic locking and an audit snapshot. */
    public View save(UUID projectId, int chapter, UUID commitId, Confirmation request) {
        transactions.executeWithoutResult(tx -> {
            var commit = requireCurrent(projectId, chapter, commitId);
            var view = lockedView(commitId);
            check(view, request.version());
            if (List.of("PENDING", "RUNNING", "SUPERSEDED").contains(view.status()))
                throw new IllegalStateException("记忆正在整理或来源失效，暂不能修改");
            String body = jdbc.queryForObject("SELECT source_body FROM canon_memory_job WHERE commit_id=?", String.class, commitId);
            if (body == null) body = names.render(projectId, manuscripts.findByIdAndProjectId(
                    commit.getManuscriptVersionId(), projectId).orElseThrow().getContent()).body();
            PublishedMemoryPolicy.validate(request.candidates(), body);
            if (request.candidates().stream().anyMatch(f -> f.decision() != FactDecision.ACCEPTED && f.decision() != FactDecision.REJECTED))
                throw new IllegalArgumentException("记忆条目只能保存或移除，不能留待确认");
            if (view.candidates().equals(request.candidates()) && "SUCCEEDED".equals(view.status())) return;
            var currentCatalog = catalog.forPublishedMemory(projectId);
            var accepted = request.candidates().stream().filter(f -> f.decision() == FactDecision.ACCEPTED).toList();
            for (var fact : accepted) if (!PublishedMemoryPolicy.autoAcceptable(fact, currentCatalog, currentCatalog))
                throw new IllegalArgumentException("记忆实体引用无法解析：" + fact.id());
            recordHistory(projectId, commitId, view, "EDIT");
            var project = access.requireOwnedProject(projectId);
            long revision = project.commitCanon(project.getCurrentCanonVersion());
            materializer.retire(commitId, revision);
            materializer.materialize(projectId, chapter, commitId, revision, accepted);
            commit.replaceMemoryFacts(accepted);
            commits.saveAndFlush(commit);
            finish(commitId, "SUCCEEDED", request.candidates());
            events.emit(commit, revision);
        });
        return status(projectId, chapter);
    }

    public List<History> history(UUID projectId, int chapter, UUID commitId) {
        return transactions.execute(tx -> {
            requireCurrent(projectId, chapter, commitId);
            return jdbc.query("SELECT id,memory_version,action,candidates,created_at FROM canon_memory_history "
                    + "WHERE commit_id=? AND project_id=? ORDER BY memory_version DESC LIMIT 20",
                    (rs, row) -> new History(rs.getObject(1, UUID.class), rs.getLong(2), rs.getString(3),
                            read(rs.getString(4)), rs.getTimestamp(5).toInstant()), commitId, projectId);
        });
    }

    private void recordHistory(UUID projectId, UUID commitId, View view, String action) {
        var snapshot = new java.util.LinkedHashMap<String, FactProposal>();
        view.candidates().forEach(f -> snapshot.put(f.id(), f));
        // A failed re-extraction may contain new drafts; preserve the still-applied memory as well.
        commits.findById(commitId).orElseThrow().getAcceptedFacts().forEach(f -> snapshot.put(f.id(), f));
        jdbc.update("INSERT INTO canon_memory_history(id,commit_id,project_id,memory_version,action,candidates) "
                + "VALUES (?,?,?,?,?,CAST(? AS jsonb))", UUID.randomUUID(), commitId, projectId, view.version(), action, write(List.copyOf(snapshot.values())));
    }
    private boolean sameClaim(FactProposal a, FactProposal b) {
        var left = mapper.valueToTree(PublishedMemoryPolicy.decide(a, FactDecision.PENDING));
        var right = mapper.valueToTree(PublishedMemoryPolicy.decide(b, FactDecision.PENDING));
        if (a.payload() != null && b.payload() != null) {
            for (String field : List.of("entityId", "sourceEntityId", "targetEntityId", "characterId")) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) left.get("payload")).remove(field);
                ((com.fasterxml.jackson.databind.node.ObjectNode) right.get("payload")).remove(field);
            }
        }
        return left.equals(right);
    }
    private com.novelagent.canon.domain.CanonCommit requireCurrent(UUID projectId, int chapter, UUID id) {
        lockProject(projectId);
        var commit = commits.findByProjectIdAndChapterNumberAndActiveTrue(projectId, chapter).orElseThrow();
        entities.refresh(commit, LockModeType.PESSIMISTIC_WRITE);
        if (!commit.isActive() || !commit.getId().equals(id)) throw new IllegalStateException("本章发布来源已变化，请刷新");
        return commit;
    }
    private void lockProject(UUID projectId) {
        var project = access.requireOwnedProject(projectId);
        entities.refresh(project, LockModeType.PESSIMISTIC_WRITE);
        access.requireOwnedProject(project);
    }
    private View lockedView(UUID id) {
        return jdbc.queryForObject("SELECT status,candidates,error_message,row_version FROM canon_memory_job WHERE commit_id=? FOR UPDATE",
                (rs, row) -> new View(id, null, rs.getString(1), read(rs.getString(2)), rs.getString(3), rs.getLong(4)), id);
    }
    private void check(View view, long version) {
        if (version != view.version()) throw new ResourceVersionConflictException(version, view.version());
    }
    private void finish(UUID id, String state, List<FactProposal> facts) {
        jdbc.update("UPDATE canon_memory_job SET status=?, candidates=CAST(? AS jsonb), error_message=NULL, "
                + "row_version=row_version+1, updated_at=now() WHERE commit_id=?", state, write(facts), id);
    }
    private List<FactProposal> read(String json) {
        try { return mapper.readValue(json, new TypeReference<List<FactProposal>>() { }); }
        catch (Exception failure) { throw new IllegalStateException("记忆条目读取失败", failure); }
    }
    private String write(Object value) {
        try { return mapper.writeValueAsString(value); }
        catch (Exception failure) { throw new IllegalStateException("记忆条目保存失败", failure); }
    }
}
