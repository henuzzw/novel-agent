package com.novelagent.project.application;

import static com.novelagent.platform.support.Text.hasText;

import com.novelagent.ingest.application.WorkImportService;
import com.novelagent.project.api.dto.CreateProjectRequest;
import com.novelagent.project.api.dto.ProjectResponse;
import com.novelagent.project.domain.CreativeBoundaries;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.EntryMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/**
 * 故事来源项目创建用例。原子保存项目和文件或文本导入记录；模型分析与规划在后续请求执行。
 */
@Service
public class StorySourceProjectService {
    private final ProjectService projects;
    private final WorkImportService imports;

    public StorySourceProjectService(ProjectService projects, WorkImportService imports) {
        this.projects = projects;
        this.imports = imports;
    }

    /**
     * 校验文件与文本二选一，在同一事务内创建项目及导入记录；未提供策略时采用默认创作策略。
     *
     * @param name 作者指定的项目名称，可为空以等待后续书名生成
     * @param strategy 作者选择的创作策略，可为空以采用默认值
     * @param file 上传的原文文件，与粘贴文本二选一
     * @param text 粘贴的原文，与上传文件二选一
     */
    @Transactional
    public ProjectResponse create(
            String name, CreativeStrategy strategy, MultipartFile file, String text) {
        return create(name, strategy, file, text, null, null);
    }

    /**
     * 校验文件与文本二选一，在同一事务内创建项目及导入记录；未提供策略时采用默认创作策略。
     *
     * @param name 作者指定的项目名称，可为空以等待后续书名生成
     * @param strategy 作者选择的创作策略，可为空以采用默认值
     * @param file 上传的原文文件，与粘贴文本二选一
     * @param text 粘贴的原文，与上传文件二选一
     * @param world 世界设定选择
     * @param scope 改编范围选择
     */
    @Transactional
    public ProjectResponse create(
            String name,
            CreativeStrategy strategy,
            MultipartFile file,
            String text,
            CreativeBoundaries.WorldMode world,
            CreativeBoundaries.AdaptationScope scope) {
        boolean hasFile = file != null && !file.isEmpty();
        boolean hasText = hasText(text);
        if (hasFile == hasText) throw new IllegalArgumentException("请选择导入文件或粘贴文字，且只提供一种来源");
        if (name != null && name.strip().length() > 200)
            throw new IllegalArgumentException("项目名称不能超过200字符");
        var project =
                projects.create(
                        new CreateProjectRequest(
                                name,
                                EntryMode.MATERIALS,
                                null,
                                strategy == null ? CreativeStrategy.FANQIE_GRIPPING : strategy,
                                world,
                                scope));
        if (hasFile) imports.upload(project.id(), file);
        else imports.paste(project.id(), text);
        return project;
    }
}
