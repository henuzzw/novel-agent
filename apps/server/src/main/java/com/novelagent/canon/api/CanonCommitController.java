package com.novelagent.canon.api;

import com.novelagent.canon.application.CanonCommitService;

import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 正史提交。
 *
 * <p>提供章节正史状态、首次提交和受控替换接口。确认正文与审稿并不自动写正史；作者必须显式提交并提供预期正史版本。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/chapters/{chapterNumber}/canon-commits")
public class CanonCommitController {
    private final CanonCommitService service;

    public CanonCommitController(CanonCommitService service) {
        this.service = service;
    }

    /**
     * 查询现有业务状态与可得进度，不执行生成、提交或投影。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     */
    @GetMapping("/status")
    public CanonCommitStatusResponse status(@PathVariable UUID projectId, @PathVariable int chapterNumber) {
        var current = service.currentCommit(projectId, chapterNumber);
        return current == null ? new CanonCommitStatusResponse(false, null, null, 0)
                : new CanonCommitStatusResponse(true, current.getId(), current.getManuscriptVersionId(),
                        current.getCanonVersion());
    }

    /**
     * 把作者确认正文和已确认审稿中接受的事实提交正史，校验预期正史版本；同一有效审稿提交可幂等返回。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping
    public ResponseEntity<CanonCommitResponse> commit(
            @PathVariable UUID projectId,
            @PathVariable int chapterNumber,
            @RequestBody CommitCanonRequest request) {
        CanonCommitResponse response = service.commit(projectId, chapterNumber, request);
        URI location = URI.create("/api/v1/projects/" + projectId + "/canon-commits/" + response.id());
        return ResponseEntity.created(location).body(response);
    }


    /**
     * 显式替换本章有效正史，要求新的确认正文、审稿及匹配的当前提交；后续章已有正史时拒绝直接替换。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param chapterNumber 章节号，从 1 开始，与版本 ID 分开定位。
     * @param request 当前接口的结构化请求，实际约束由本方法及领域校验执行。
     */
    @PostMapping("/actions/replace")
    public CanonCommitResponse replace(@PathVariable UUID projectId, @PathVariable int chapterNumber,
            @RequestBody ReplaceCanonRequest request) {
        return service.replace(projectId, chapterNumber, request);
    }
}
