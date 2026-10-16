package com.novelagent.canon.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.infrastructure.PublishedManuscriptExportRepository;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.infrastructure.OutlineVersionRepository;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.writing.domain.ManuscriptContent;
import java.util.HashMap;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** 在同一只读快照中导出全书；不生成、确认、发布或更新任何记忆。 */
@Service
public class PublishedManuscriptExportService {
    private final ProjectAccessService access;
    private final PublishedManuscriptExportRepository chapters;
    private final OutlineVersionRepository outlines;
    private final CharacterNameService names;
    private final ObjectMapper mapper;

    public PublishedManuscriptExportService(ProjectAccessService access, PublishedManuscriptExportRepository chapters,
            OutlineVersionRepository outlines, CharacterNameService names, ObjectMapper mapper) {
        this.access = access; this.chapters = chapters; this.outlines = outlines;
        this.names = names; this.mapper = mapper;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public String export(UUID projectId) {
        var project = access.requireOwnedProject(projectId);
        var completed = chapters.completedChapters(projectId);
        if (completed.isEmpty()) throw new IllegalStateException("暂无已发布且记忆整理完成的正文可导出");
        var sourceOutlines = new HashMap<UUID, OutlineContent>();
        var result = new StringBuilder("# ").append(heading(project.getName())).append("\n\n");
        String previousVolume = null;
        for (var chapter : completed) {
            String volume = "未分卷";
            if (chapter.outlineId() != null) {
                var outline = sourceOutlines.computeIfAbsent(chapter.outlineId(), id -> outlines.findByIdAndProjectId(id, projectId)
                        .orElseThrow(() -> new IllegalStateException("已发布正文的来源大纲不存在，无法导出卷名")).getContent());
                volume = outline.arcs().stream().filter(arc -> arc.chapters().stream()
                        .anyMatch(plan -> plan.number() == chapter.number())).findFirst()
                        .map(arc -> heading(arc.title())).orElse("未分卷");
            }
            if (!Objects.equals(previousVolume, volume)) {
                result.append("## ").append(volume).append("\n\n");
                previousVolume = volume;
            }
            var content = readContent(chapter.content());
            result.append("### 第 ").append(chapter.number()).append(" 章 · ").append(heading(content.title()))
                    .append("\n\n").append(content.body()).append("\n\n");
        }
        // One name-map read keeps all chapters consistent and avoids per-chapter lookup queries.
        return names.render(projectId, result.toString());
    }

    private ManuscriptContent readContent(String value) {
        try { return mapper.readValue(value, ManuscriptContent.class); }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException("已发布正文存储格式异常，无法导出");
        }
    }

    private static String heading(String value) { return value.replace('\r', ' ').replace('\n', ' ').trim(); }
}
