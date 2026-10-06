package com.novelagent.ingest.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.ingest.api.ImportedChapterResponse;
import com.novelagent.ingest.api.WorkImportResponse;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ProjectNotFoundException;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.xml.sax.ContentHandler;

/**
 * 作品导入。
 *
 * <p>保存原始字节、提取 TXT/Markdown/DOCX/文本型 PDF 并识别章节。确认选区后规划读取完整选中原文；格式解析与小说语义分析是不同阶段。</p>
 */
@Service
public class WorkImportService {
    private static final String PARSER_VERSION = "tika-2.9.2+chapter-v1";
    private static final Pattern CHAPTER_HEADING = Pattern.compile(
            "(?m)^[\\t ]*((?:第[零一二三四五六七八九十百千万0-9]{1,12}[章节卷回部篇][^\\r\\n]{0,80})|(?:Chapter\\s+\\d+[^\\r\\n]{0,80}))[\\t ]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
    private static final List<String> ALLOWED_EXTENSIONS = List.of("txt", "md", "docx", "pdf");

    private final NovelProjectRepository projects;
    private final CurrentActorProvider actor;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final long maxFileBytes;

    public WorkImportService(NovelProjectRepository projects, CurrentActorProvider actor, JdbcTemplate jdbc,
            ObjectMapper mapper, @Value("${app.import.max-file-bytes:20971520}") long maxFileBytes) {
        this.projects = projects;
        this.actor = actor;
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.maxFileBytes = maxFileBytes;
    }

    /**
     * 检查大小及文件类型，保存原文件和提取文本，识别章节并记录警告；这里只完成格式识别，尚未调用模型解析人物与世界。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param file 上传的原始文件，仍须经过类型和大小检查。
     */
    @Transactional
    public WorkImportResponse upload(UUID projectId, MultipartFile file) {
        requireOwnedProject(projectId);
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择需要导入的文件");
        if (file.getSize() > maxFileBytes) throw new IllegalArgumentException("导入文件不能超过 20 MB");
        String filename = safeFilename(file.getOriginalFilename());
        String extension = extension(filename);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("仅支持 txt、md、docx 和文本型 pdf 文件");
        }
        try {
            byte[] source = file.getBytes();
            String hash = sha256(source);
            UUID existing = jdbc.query("SELECT id FROM work_import WHERE project_id = ? AND sha256 = ?",
                    rs -> rs.next() ? rs.getObject(1, UUID.class) : null, projectId, hash);
            if (existing != null) return get(projectId, existing);

            ParsedText parsed = parse(source, filename);
            List<String> warnings = new ArrayList<>();
            if (parsed.text().isBlank()) {
                throw new IllegalArgumentException("文件中没有可读取的文本；扫描版 PDF 暂不支持 OCR");
            }
            List<ChapterPart> chapters = splitChapters(parsed.text(), filename, warnings);
            String contentType = detectContentType(filename, parsed.text());
            UUID importId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO work_import(id, project_id, original_filename, media_type, size_bytes, sha256,
                        parser_version, detected_content_type, status, original_content, extracted_text, warnings)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PARSED', ?, ?, CAST(? AS jsonb))
                    """, importId, projectId, filename, parsed.mediaType(), source.length, hash,
                    PARSER_VERSION, contentType, source, parsed.text(), mapper.writeValueAsString(warnings));
            for (ChapterPart chapter : chapters) {
                jdbc.update("""
                        INSERT INTO imported_chapter(id, import_id, project_id, ordinal, title, content,
                            character_count, content_type, selected)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                        """, UUID.randomUUID(), importId, projectId, chapter.ordinal(), chapter.title(),
                        chapter.content(), chapter.content().codePointCount(0, chapter.content().length()), contentType);
            }
            return get(projectId, importId);
        } catch (IOException exception) {
            throw new IllegalArgumentException("无法读取上传文件", exception);
        }
    }

    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @Transactional(readOnly = true)
    public List<WorkImportResponse> list(UUID projectId) {
        requireOwnedProject(projectId);
        return jdbc.query("SELECT id FROM work_import WHERE project_id = ? ORDER BY created_at DESC",
                (rs, row) -> get(projectId, rs.getObject("id", UUID.class)), projectId);
    }

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param importId 导入文件记录 ID，必须与指定项目匹配。
     */
    @Transactional(readOnly = true)
    public WorkImportResponse get(UUID projectId, UUID importId) {
        requireOwnedProject(projectId);
        return jdbc.queryForObject("""
                SELECT id, project_id, original_filename, media_type, size_bytes, sha256, parser_version,
                       detected_content_type, status, planning_status, planning_mode, generated_bible_version_id,
                       generated_outline_version_id, planning_error, warnings, created_at, confirmed_at
                FROM work_import WHERE id = ? AND project_id = ?
                """, (rs, row) -> new WorkImportResponse(
                rs.getObject("id", UUID.class), rs.getObject("project_id", UUID.class),
                rs.getString("original_filename"), rs.getString("media_type"), rs.getLong("size_bytes"),
                rs.getString("sha256"), rs.getString("parser_version"), rs.getString("detected_content_type"),
                rs.getString("status"), rs.getString("planning_status"), rs.getString("planning_mode"),
                rs.getObject("generated_bible_version_id", UUID.class),
                rs.getObject("generated_outline_version_id", UUID.class), rs.getString("planning_error"),
                warnings(rs.getString("warnings")), chapters(importId),
                rs.getTimestamp("created_at").toInstant(), instant(rs.getTimestamp("confirmed_at"))), importId, projectId);
    }

    /**
     * 确认识别出的导入章节可供后续解析，保留原文件；此确认不自动生成故事圣经或发布规划。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param importId 导入文件记录 ID，必须与指定项目匹配。
     */
    @Transactional
    public WorkImportResponse confirm(UUID projectId, UUID importId) {
        requireOwnedProject(projectId);
        int updated = jdbc.update("""
                UPDATE work_import SET status = 'CONFIRMED', confirmed_at = COALESCE(confirmed_at, now())
                WHERE id = ? AND project_id = ?
                """, importId, projectId);
        if (updated == 0) throw new IllegalArgumentException("导入记录不存在");
        return get(projectId, importId);
    }

    /**
     * 读取原始或已确认来源资料并保留来源版本，供下载、证据引用或后续业务复核。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param importId 导入文件记录 ID，必须与指定项目匹配。
     */
    @Transactional(readOnly = true)
    public SourceFile source(UUID projectId, UUID importId) {
        requireOwnedProject(projectId);
        return jdbc.queryForObject("""
                SELECT original_filename, COALESCE(media_type, 'application/octet-stream') AS media_type, original_content
                FROM work_import WHERE id = ? AND project_id = ?
                """, (rs, row) -> new SourceFile(rs.getString("original_filename"), rs.getString("media_type"),
                rs.getBytes("original_content")), importId, projectId);
    }

    /**
     * 读取作者确认的导入章节原文供规划使用；读取完整选中范围，不将截断样本冒充完整原文。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param importId 导入文件记录 ID，必须与指定项目匹配。
     */
    @Transactional(readOnly = true)
    public PlanningSource planningSource(UUID projectId, UUID importId) {
        requireOwnedProject(projectId);
        String status = jdbc.queryForObject(
                "SELECT status FROM work_import WHERE id = ? AND project_id = ?", String.class, importId, projectId);
        if (!"CONFIRMED".equals(status)) throw new IllegalArgumentException("请先确认导入结果，再反推规划");
        List<ImportedChapterResponse> selected = jdbc.query("""
                SELECT id, ordinal, title, content, character_count, content_type, selected
                  FROM imported_chapter WHERE import_id = ? AND selected = TRUE ORDER BY ordinal
                """, (rs, row) -> new ImportedChapterResponse(rs.getObject("id", UUID.class),
                rs.getInt("ordinal"), rs.getString("title"), rs.getString("content"),
                rs.getInt("character_count"), rs.getString("content_type"), rs.getBoolean("selected")), importId);
        if (selected.isEmpty()) throw new IllegalArgumentException("没有选中可用于反推规划的章节");
        StringBuilder text = new StringBuilder();
        for (ImportedChapterResponse chapter : selected) {
            String block = "\n\n## 已写第 " + chapter.ordinal() + " 章：" + chapter.title() + "\n" + chapter.content();
            text.append(block);
        }
        return new PlanningSource(text.toString().strip(), selected.size(),
                selected.stream().mapToInt(ImportedChapterResponse::characterCount).sum(), false);
    }

    private ParsedText parse(byte[] source, String filename) {
        try (ByteArrayInputStream input = new ByteArrayInputStream(source)) {
            AutoDetectParser parser = new AutoDetectParser();
            ContentHandler handler = new BodyContentHandler(-1);
            Metadata metadata = new Metadata();
            metadata.set("resourceName", filename);
            parser.parse(input, handler, metadata);
            String mediaType = metadata.get(Metadata.CONTENT_TYPE);
            return new ParsedText(normalize(handler.toString()), mediaType == null ? "application/octet-stream" : mediaType);
        } catch (Exception exception) {
            throw new IllegalArgumentException("文件解析失败，请确认文件未损坏且不是扫描版 PDF", exception);
        }
    }

    /**
     * 按受支持的章标题分割提取文本；未识别标题时保留完整文本为一个单元并交作者核对，不丢弃原文。
     *
     * @param text 待渲染、检索或嵌入的文本，不自动成为正史事实。
     * @param filename 上传文件名，用于格式识别，不当成可信磁盘路径。
     * @param warnings 本次校验或消歧过程中累积的警告。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    private static List<ChapterPart> splitChapters(String text, String filename, List<String> warnings) {
        Matcher matcher = CHAPTER_HEADING.matcher(text);
        List<Heading> headings = new ArrayList<>();
        while (matcher.find()) headings.add(new Heading(matcher.start(), matcher.end(), matcher.group(1).trim()));
        if (headings.isEmpty()) {
            warnings.add("未识别到章节标题，已将全文作为一个章节。可在后续导入编辑器中拆分。");
            return List.of(new ChapterPart(1, baseName(filename), text.strip()));
        }
        List<ChapterPart> chapters = new ArrayList<>();
        String prefix = text.substring(0, headings.getFirst().start()).strip();
        if (!prefix.isBlank()) chapters.add(new ChapterPart(chapters.size() + 1, "前言", prefix));
        for (int index = 0; index < headings.size(); index++) {
            Heading heading = headings.get(index);
            int end = index + 1 < headings.size() ? headings.get(index + 1).start() : text.length();
            String content = text.substring(heading.end(), end).strip();
            if (!content.isBlank()) chapters.add(new ChapterPart(chapters.size() + 1, heading.title(), content));
        }
        if (chapters.isEmpty()) {
            warnings.add("识别到章节标题，但章节正文为空。");
            chapters.add(new ChapterPart(1, baseName(filename), text.strip()));
        }
        return List.copyOf(chapters);
    }

    private static String detectContentType(String filename, String text) {
        String sample = (filename + "\n" + text.substring(0, Math.min(2000, text.length()))).toLowerCase(Locale.ROOT);
        if (sample.contains("人物设定") || sample.contains("世界观") || sample.contains("角色设定")) return "MATERIALS";
        if (sample.contains("大纲") || sample.contains("章节规划") || sample.contains("剧情梗概")) return "OUTLINE";
        return "MANUSCRIPT";
    }

    private List<ImportedChapterResponse> chapters(UUID importId) {
        return jdbc.query("""
                SELECT id, ordinal, title, content, character_count, content_type, selected
                FROM imported_chapter WHERE import_id = ? ORDER BY ordinal
                """, (rs, row) -> new ImportedChapterResponse(rs.getObject("id", UUID.class),
                rs.getInt("ordinal"), rs.getString("title"), rs.getString("content"),
                rs.getInt("character_count"), rs.getString("content_type"), rs.getBoolean("selected")), importId);
    }

    private void requireOwnedProject(UUID projectId) {
        projects.findById(projectId)
                .filter(project -> project.getOwnerId().equals(actor.currentUserId()))
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
    }

    private List<String> warnings(String json) {
        try {
            return mapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("导入报告损坏", exception);
        }
    }

    private static String safeFilename(String value) {
        String normalized = value == null ? "未命名文件" : value.replace('\\', '/');
        normalized = normalized.substring(normalized.lastIndexOf('/') + 1).strip();
        return normalized.isBlank() ? "未命名文件" : normalized;
    }

    private static String extension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String baseName(String filename) {
        int dot = filename.lastIndexOf('.');
        return (dot <= 0 ? filename : filename.substring(0, dot)).strip();
    }

    private static String normalize(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[\\t ]+\\n", "\n").replaceAll("\\n{4,}", "\n\n\n").strip();
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("运行环境不支持 SHA-256", exception);
        }
    }

    private static Instant instant(Timestamp value) {
        return value == null ? null : value.toInstant();
    }

    public record SourceFile(String filename, String mediaType, byte[] content) {
    }

    public record PlanningSource(String text, int chapterCount, int characterCount, boolean truncated) {
    }

    private record ParsedText(String text, String mediaType) {
    }

    private record Heading(int start, int end, String title) {
    }

    private record ChapterPart(int ordinal, String title, String content) {
    }
}
