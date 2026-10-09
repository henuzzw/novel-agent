package com.novelagent.project.application;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.infrastructure.FreeTextPlanningRequest;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Explicitly recorded title request, outside the import claim transaction, without an output schema. */
@Service
public class BookTitleService {
    private final ProjectAccessService access;
    private final JdbcTemplate jdbc;
    private final FreeTextPlanningRequest texts;
    public BookTitleService(ProjectAccessService access, JdbcTemplate jdbc, FreeTextPlanningRequest texts) {
        this.access = access; this.jdbc = jdbc; this.texts = texts;
    }
    public void generateIfNeeded(UUID projectId, UUID importId, ModelProvider provider) {
        var project = access.requireOwnedProject(projectId);
        if (!(project.getSetting("automaticTitle") instanceof Map<?, ?> value)
                || !Boolean.TRUE.equals(value.get("pending"))) return;
        String source = jdbc.queryForObject("SELECT extracted_text FROM work_import WHERE project_id = ? AND id = ?",
                String.class, projectId, importId);
        String title = texts.request(projectId, "BOOK_TITLE", provider, "故事内容\n" + source, "BOOK_TITLE", 1000).strip();
        if (title.isBlank() || title.length() > 200 || title.contains("\n") || title.contains("\r")
                || title.startsWith("#") || title.startsWith("```")) {
            throw new IllegalArgumentException("书名响应无效：应只返回一个书名，不包含解释或分行");
        }
        // A late title cannot replace an author-edited title or another successful result.
        jdbc.update("""
                UPDATE novel_project SET name = ?, settings = jsonb_set(settings, '{automaticTitle}', '{"pending":false}'::jsonb),
                    row_version = row_version + 1, updated_at = CURRENT_TIMESTAMP
                WHERE id = ? AND name = ? AND settings->'automaticTitle'->>'pending' = 'true'
                """, title, projectId, project.getName());
    }
}
