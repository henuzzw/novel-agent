package com.novelagent.ingest.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.ingest.api.ImportedChapterResponse;
import com.novelagent.ingest.domain.ImportAnalysis;
import com.novelagent.ingest.domain.ImportPlanningMode;
import com.novelagent.ingest.infrastructure.ImportAnalysisOutputParser;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.writing.application.WritingResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ImportAnalysisStore {
    private final ProjectAccessService access;
    private final WorkImportService imports;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final ImportAnalysisOutputParser parser;
    public ImportAnalysisStore(ProjectAccessService access, WorkImportService imports, JdbcTemplate jdbc, ObjectMapper mapper, ImportAnalysisOutputParser parser) {
        this.access = access; this.imports = imports; this.jdbc = jdbc; this.mapper = mapper; this.parser = parser;
    }
    public record Create(UUID requestId, ModelProvider provider) { }
    public record Confirm(long version, boolean authorConfirmed, ImportPlanningMode mode, List<ImportAnalysis.Decision> decisions) { }
    public record Report(UUID id, UUID projectId, UUID importId, ModelProvider provider, String sourceHash,
            List<ImportAnalysis.Slice> slices, int nextSlice, String status, ImportAnalysis.Content content,
            List<ImportAnalysis.Decision> decisions, ImportPlanningMode confirmedMode, String errorMessage, long version, Instant updatedAt) { }
    public record View(Report report, boolean stale) { }
    public record Claim(Report report, String input) { }
    @Transactional(readOnly = true)
    public List<View> list(UUID projectId, UUID importId) {
        var chapters = source(projectId, importId); String hash = fingerprint(chapters);
        return jdbc.query("SELECT * FROM import_analysis_report WHERE project_id = ? AND import_id = ? ORDER BY created_at DESC LIMIT 10",
                (rs, n) -> new View(row(rs), !rs.getString("source_hash").equals(hash)), projectId, importId);
    }
    @Transactional(readOnly = true)
    public View get(UUID projectId, UUID importId, UUID id) {
        var chapters = source(projectId, importId); var report = require(projectId, importId, id);
        return new View(report, !report.sourceHash().equals(fingerprint(chapters)));
    }
    @Transactional
    public View create(UUID projectId, UUID importId, Create input) {
        lock(projectId); var chapters = source(projectId, importId);
        if (input == null || input.requestId() == null || input.provider() == null || !List.of(ModelProvider.DEEPSEEK, ModelProvider.LOCAL_CODEX).contains(input.provider())) throw new IllegalArgumentException("请选择真实模型并提供请求标识");
        String hash = fingerprint(chapters);
        var existing = jdbc.query("SELECT * FROM import_analysis_report WHERE project_id = ? AND import_id = ? AND request_id = ?", (rs, n) -> row(rs), projectId, importId, input.requestId());
        if (!existing.isEmpty()) {
            var report = existing.getFirst();
            if (report.provider() != input.provider() || !report.sourceHash().equals(hash)) throw new IllegalArgumentException("请求标识对应的解析参数或来源已改变");
            return new View(report, false);
        }
        var slices = ImportAnalysis.slices(chapters); UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO import_analysis_report(id, project_id, import_id, request_id, provider, source_hash, slices) VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb))",
                id, projectId, importId, input.requestId(), input.provider().name(), hash, json(slices));
        return get(projectId, importId, id);
    }
    @Transactional
    public Claim claim(UUID projectId, UUID importId, UUID id, long version) {
        lock(projectId); var report = require(projectId, importId, id); version(report, version);
        var chapters = fresh(report);
        if (!report.status().equals("READY") || report.nextSlice() >= report.slices().size()) throw new IllegalArgumentException("解析当前不能执行，请刷新或明确恢复");
        if (jdbc.queryForObject("SELECT count(*) FROM import_analysis_report WHERE project_id = ? AND status = 'RUNNING'", Integer.class, projectId) > 0) throw new IllegalArgumentException("本项目已有原文解析正在运行");
        jdbc.update("UPDATE import_analysis_report SET status = 'RUNNING', error_message = NULL, row_version = row_version + 1, updated_at = now() WHERE id = ?", id);
        report = require(projectId, importId, id); var slice = report.slices().get(report.nextSlice());
        var chapter = chapters.stream().filter(value -> value.id().equals(slice.chapterId())).findFirst().orElseThrow();
        var input = mapper.createObjectNode(); input.set("slice", mapper.valueToTree(slice));
        input.put("part", report.nextSlice() + 1); input.put("totalParts", report.slices().size());
        input.put("text", chapter.content().substring(slice.start(), slice.end()));
        return new Claim(report, input.toString());
    }
    @Transactional
    public View finish(Claim claim, String output) {
        var expected = claim.report(); lock(expected.projectId()); var report = require(expected.projectId(), expected.importId(), expected.id());
        version(report, expected.version()); var chapters = fresh(report);
        if (!report.status().equals("RUNNING")) throw new IllegalArgumentException("任务已取消或恢复，拒绝迟到解析结果");
        var batch = parser.parse(output);
        batch = ImportAnalysis.normalizeBatch(batch, report.slices().get(report.nextSlice()), chapters);
        int next = report.nextSlice() + 1; var content = report.content().append(batch, report.nextSlice());
        jdbc.update("UPDATE import_analysis_report SET content = CAST(? AS jsonb), next_slice = ?, status = ?, row_version = row_version + 1, updated_at = now() WHERE id = ?",
                json(content), next, next == report.slices().size() ? "REVIEW" : "READY", report.id());
        return get(report.projectId(), report.importId(), report.id());
    }
    @Transactional
    public void fail(Claim claim, RuntimeException error) {
        var detail = com.novelagent.agent.application.ModelFailureDetails.from(error);
        jdbc.update("UPDATE import_analysis_report SET status = ?, error_message = ?, row_version = row_version + 1, updated_at = now() WHERE id = ? AND status = 'RUNNING' AND row_version = ?",
                error instanceof com.novelagent.agent.application.GenerationStoppedException ? "CANCELLED" : "FAILED",
                detail.type() + ": " + detail.detail(), claim.report().id(), claim.report().version());
    }
    @Transactional
    public View action(UUID projectId, UUID importId, UUID id, long version, String action) {
        lock(projectId); var report = require(projectId, importId, id); version(report, version);
        String status;
        if (action.equals("resume")) {
            fresh(report);
            if (!report.status().equals("FAILED") && !(report.status().equals("RUNNING") && report.updatedAt().isBefore(Instant.now().minusSeconds(1500)))) throw new IllegalArgumentException("仅失败或超过 25 分钟的运行解析可以恢复");
            status = "READY";
        } else {
            if (List.of("CONFIRMED", "CANCELLED").contains(report.status())) throw new IllegalArgumentException("已结束的解析不能取消");
            status = "CANCELLED";
        }
        jdbc.update("UPDATE import_analysis_report SET status = ?, row_version = row_version + 1, updated_at = now() WHERE id = ?", status, id);
        return get(projectId, importId, id);
    }
    @Transactional
    public View confirm(UUID projectId, UUID importId, UUID id, Confirm input) {
        lock(projectId); var report = require(projectId, importId, id);
        if (input == null || !input.authorConfirmed()) throw new IllegalArgumentException("请明确确认解析报告");
        version(report, input.version()); fresh(report);
        if (!List.of("REVIEW", "CONFIRMED").contains(report.status()) || report.nextSlice() != report.slices().size()) throw new IllegalArgumentException("必须完成全部原文解析后才能确认");
        ImportAnalysis.validateDecisions(report.content(), input.decisions(), input.mode());
        jdbc.update("UPDATE import_analysis_report SET decisions = CAST(? AS jsonb), confirmed_mode = ?, status = 'CONFIRMED', row_version = row_version + 1, updated_at = now() WHERE id = ?", json(input.decisions()), input.mode().name(), id);
        jdbc.update("UPDATE work_import SET confirmed_analysis_id = ?, status = 'CONFIRMED', confirmed_at = COALESCE(confirmed_at, now()) WHERE id = ? AND project_id = ?", id, importId, projectId);
        return get(projectId, importId, id);
    }
    @Transactional(readOnly = true)
    public JsonNode requireConfirmed(UUID projectId, UUID importId, UUID id, Long version, ImportPlanningMode mode) {
        source(projectId, importId);
        if (id == null || version == null) throw new IllegalArgumentException("请先解析原文并确认报告，再生成规划");
        var report = require(projectId, importId, id); version(report, version); fresh(report);
        UUID current = jdbc.queryForObject("SELECT confirmed_analysis_id FROM work_import WHERE id = ? AND project_id = ?", UUID.class, importId, projectId);
        if (!report.status().equals("CONFIRMED") || report.confirmedMode() != mode || !id.equals(current)) throw new IllegalArgumentException("解析报告未确认、已被替换或使用方式不同，请重新确认");
        var result = mapper.createObjectNode(); result.put("reportId", id.toString()); result.put("version", version);
        result.put("mode", mode.name()); result.set("content", mapper.valueToTree(report.content())); result.set("decisions", mapper.valueToTree(report.decisions()));
        return result;
    }
    public void lock(UUID projectId) {
        access.requireOwnedProject(projectId);
        jdbc.queryForObject("SELECT id FROM novel_project WHERE id = ? FOR UPDATE", UUID.class, projectId);
    }
    private List<ImportAnalysis.Chapter> source(UUID projectId, UUID importId) {
        access.requireOwnedProject(projectId);
        if (jdbc.queryForObject("SELECT count(*) FROM work_import WHERE id = ? AND project_id = ?", Integer.class, importId, projectId) == 0) throw new WritingResourceNotFoundException("导入", importId);
        var chapters = imports.get(projectId, importId).chapters().stream().filter(ImportedChapterResponse::selected)
                .map(value -> new ImportAnalysis.Chapter(value.id(), value.ordinal(), value.title(), value.content())).toList();
        if (chapters.isEmpty()) throw new IllegalArgumentException("没有选中的原文章节");
        return chapters;
    }
    private List<ImportAnalysis.Chapter> fresh(Report report) {
        var chapters = source(report.projectId(), report.importId());
        if (!report.sourceHash().equals(fingerprint(chapters))) throw new IllegalArgumentException("原文或章节选择已变化，请重新解析");
        return chapters;
    }
    private Report require(UUID projectId, UUID importId, UUID id) {
        return jdbc.query("SELECT * FROM import_analysis_report WHERE id = ? AND project_id = ? AND import_id = ?", (rs, n) -> row(rs), id, projectId, importId).stream().findFirst().orElseThrow(() -> new WritingResourceNotFoundException("原文解析", id));
    }
    private Report row(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Report(rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class), rs.getObject("import_id", UUID.class),
                ModelProvider.valueOf(rs.getString("provider")), rs.getString("source_hash"),
                read(rs.getString("slices"), new TypeReference<List<ImportAnalysis.Slice>>() { }), rs.getInt("next_slice"), rs.getString("status"),
                read(rs.getString("content"), ImportAnalysis.Content.class), read(rs.getString("decisions"), new TypeReference<List<ImportAnalysis.Decision>>() { }),
                rs.getString("confirmed_mode") == null ? null : ImportPlanningMode.valueOf(rs.getString("confirmed_mode")), rs.getString("error_message"), rs.getLong("row_version"), rs.getTimestamp("updated_at").toInstant());
    }
    private String fingerprint(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json(value).getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (JsonProcessingException e) { throw new IllegalArgumentException("解析报告格式错误", e); } }
    private <T> T read(String value, Class<T> type) { try { return mapper.readValue(value, type); } catch (JsonProcessingException e) { throw new IllegalArgumentException("解析报告格式错误", e); } }
    private <T> T read(String value, TypeReference<T> type) { try { return mapper.readValue(value, type); } catch (JsonProcessingException e) { throw new IllegalArgumentException("解析报告格式错误", e); } }
    private static void version(Report report, long version) { if (report.version() != version) throw new ResourceVersionConflictException(version, report.version()); }
}
