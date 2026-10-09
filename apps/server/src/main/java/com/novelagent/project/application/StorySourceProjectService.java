package com.novelagent.project.application;

import com.novelagent.ingest.application.WorkImportService;
import com.novelagent.project.api.CreateProjectRequest;
import com.novelagent.project.api.ProjectResponse;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.EntryMode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

/** Creates project and original source atomically; model waits happen later in the import task. */
@Service
public class StorySourceProjectService {
    private final ProjectService projects;
    private final WorkImportService imports;
    public StorySourceProjectService(ProjectService projects, WorkImportService imports) {
        this.projects = projects; this.imports = imports;
    }
    @Transactional
    public ProjectResponse create(String name, CreativeStrategy strategy, MultipartFile file, String text) {
        boolean hasFile = file != null && !file.isEmpty();
        boolean hasText = text != null && !text.isBlank();
        if (hasFile == hasText) throw new IllegalArgumentException("请选择导入文件或粘贴文字，且只提供一种来源");
        if (name != null && name.strip().length() > 200) throw new IllegalArgumentException("项目名称不能超过200字符");
        var project = projects.create(new CreateProjectRequest(name, EntryMode.MATERIALS, null,
                strategy == null ? CreativeStrategy.FANQIE_GRIPPING : strategy));
        if (hasFile) imports.upload(project.id(), file);
        else imports.paste(project.id(), text);
        return project;
    }
}
