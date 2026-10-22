package com.novelagent.project.application;

import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.modelaccess.application.FreeTextModelRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * 按需生成项目书名。独立记录模型调用，不占用导入认领事务；保存时防止迟到响应覆盖作者已编辑的书名。
 */
@Service
public class BookTitleService {
    private final ProjectAccessService access;
    private final JdbcTemplate jdbc;
    private final FreeTextModelRequest texts;

    public BookTitleService(
            ProjectAccessService access, JdbcTemplate jdbc, FreeTextModelRequest texts) {
        this.access = access;
        this.jdbc = jdbc;
        this.texts = texts;
    }

    /**
     * 仅在自动书名标记待处理时读取导入原文并请求书名；校验单行长度后按原名称和待处理标记条件保存。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param importId 当前项目的导入来源 ID
     * @param provider 本次请求使用的模型供应商
     */
    public void generateIfNeeded(UUID projectId, UUID importId, ModelProvider provider) {
        var project = access.requireOwnedProject(projectId);
        if (!(project.getSetting("automaticTitle") instanceof Map<?, ?> value)
                || !Boolean.TRUE.equals(value.get("pending"))) return;
        String source =
                jdbc.queryForObject(
                        "SELECT extracted_text FROM work_import WHERE project_id = ? AND id = ?",
                        String.class,
                        projectId,
                        importId);
        String title =
                texts.request(
                                projectId,
                                "BOOK_TITLE",
                                provider,
                                "故事内容\n" + source,
                                "BOOK_TITLE",
                                1000)
                        .strip();
        if (title.isBlank()
                || title.length() > 200
                || title.contains("\n")
                || title.contains("\r")
                || title.startsWith("#")
                || title.startsWith("```")) {
            throw new IllegalArgumentException("书名响应无效：应只返回一个书名，不包含解释或分行");
        }
        // A late title cannot replace an author-edited title or another successful result.
        jdbc.update(
                """
                UPDATE novel_project SET name = ?, settings = jsonb_set(settings, '{automaticTitle}', '{"pending":false}'::jsonb),
                    row_version = row_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND name = ? AND settings->'automaticTitle'->>'pending' = 'true'
                """,
                title,
                projectId,
                project.getName());
    }
}
