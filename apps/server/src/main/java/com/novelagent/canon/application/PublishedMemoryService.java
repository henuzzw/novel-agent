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
    record Source(UUID commitId, UUID projectId, ModelProvider provider, ManuscriptContent manuscript,
            EntityCatalogContext catalog) { }
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
                        AND older.status IN ('PENDING','RUNNING'))
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
                    catalog.forPublishedMemory(commit.getProjectId()));
        });
    }

    void process(Source source) {
        try {
            var result = gateway.publishedMemory(source.projectId(), source.manuscript(), source.catalog(), source.provider());
            PublishedMemoryPolicy.validate(result.factProposals(), source.manuscript().body());
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
                var candidates = result.factProposals().stream().map(f -> PublishedMemoryPolicy.decide(f,
                        PublishedMemoryPolicy.unambiguous(f, source.catalog()) && PublishedMemoryPolicy.unambiguous(f, currentCatalog)
                                ? FactDecision.ACCEPTED : FactDecision.PENDING)).toList();
                var accepted = candidates.stream().filter(f -> f.decision() == FactDecision.ACCEPTED).toList();
                if (!accepted.isEmpty()) {
                    materializer.materialize(source.projectId(), commit.getChapterNumber(), commit.getId(), commit.getCanonVersion(), accepted);
                    commit.addMemoryFacts(accepted);
                    commits.saveAndFlush(commit);
                    events.emit(commit); // A fresh event lets projections observe the newly added index entries.
                }
                finish(commit.getId(), candidates.stream().anyMatch(f -> f.decision() == FactDecision.PENDING)
                        ? "NEEDS_CONFIRMATION" : "SUCCEEDED", candidates);
            });
        } catch (Exception failure) {
            jdbc.update("UPDATE canon_memory_job SET status='FAILED', error_message='记忆整理未完成，可查看生成记录并手动重试', "
                    + "row_version=row_version+1, updated_at=now() WHERE commit_id=? AND status='RUNNING'", source.commitId());
        }
    }

    public View retry(UUID projectId, int chapter, UUID commitId, long version) {
        transactions.executeWithoutResult(tx -> {
            requireCurrent(projectId, chapter, commitId);
            var view = lockedView(commitId);
            check(view, version);
            if (!"FAILED".equals(view.status())) throw new IllegalStateException("只有失败或中断的记忆整理可以重试");
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
