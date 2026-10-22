package com.novelagent.project.api;

import com.novelagent.project.api.dto.ProjectResponse;
import com.novelagent.project.application.StorySourceProjectService;
import com.novelagent.project.domain.CreativeBoundaries;
import com.novelagent.project.domain.CreativeStrategy;
import java.net.URI;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 从故事来源创建项目的入口。文件与粘贴文本二选一，项目与导入原文在同一事务保存，后续规划另行发起。
 */
@RestController
@RequestMapping("/api/v1/projects")
public class StorySourceProjectController {
    private final StorySourceProjectService service;

    public StorySourceProjectController(StorySourceProjectService service) {
        this.service = service;
    }

    /**
     * 接收文件或粘贴原文及创作边界，原子创建项目和导入来源；不调用模型生成规划。
     *
     * @param name 作者指定的项目名称，可为空以等待后续书名生成
     * @param creativeStrategy 作者选择的创作策略
     * @param worldMode 世界设定选择
     * @param adaptationScope 改编范围选择
     * @param file 上传的原文文件，与粘贴文本二选一
     * @param text 粘贴的原文，与上传文件二选一
     */
    @PostMapping(value = "/from-story", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ProjectResponse> create(
            @RequestParam(required = false) String name,
            @RequestParam(required = false) CreativeStrategy creativeStrategy,
            @RequestParam(required = false) CreativeBoundaries.WorldMode worldMode,
            @RequestParam(required = false) CreativeBoundaries.AdaptationScope adaptationScope,
            @RequestPart(required = false) MultipartFile file,
            @RequestParam(required = false) String text) {
        var project =
                service.create(name, creativeStrategy, file, text, worldMode, adaptationScope);
        return ResponseEntity.created(URI.create("/api/v1/projects/" + project.id())).body(project);
    }
}
