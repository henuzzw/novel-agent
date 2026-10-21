package com.novelagent.canon.api;

import com.novelagent.canon.application.PublishedManuscriptExportService;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 下载所属项目已完成记忆整理的有效正文；授权与快照读取由业务层负责。 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/canon-commits")
public class PublishedManuscriptExportController {
    private final PublishedManuscriptExportService service;

    public PublishedManuscriptExportController(PublishedManuscriptExportService service) {
        this.service = service;
    }

    /**
     * 下载当前有效发布且记忆整理成功的章节 Markdown；响应禁止缓存，不包含草稿或记忆失败章节。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     */
    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID projectId) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "markdown", StandardCharsets.UTF_8))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment()
                                .filename("已完成正文.md", StandardCharsets.UTF_8)
                                .build()
                                .toString())
                .body(service.export(projectId).getBytes(StandardCharsets.UTF_8));
    }
}
