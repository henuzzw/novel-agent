package com.novelagent.canon.api;

import com.novelagent.canon.api.dto.CanonCommitResponse;
import com.novelagent.canon.api.dto.PublishManuscriptRequest;
import com.novelagent.canon.application.CanonPublicationService;
import com.novelagent.canon.application.PublishedMemoryService;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * 正文发布与发布后记忆管理接口。委托应用服务校验项目归属、来源版本及当前有效提交；记忆整理异步执行。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/chapters/{chapterNumber}/canon-commits")
public class CanonPublicationController {
    private final CanonPublicationService publication;
    private final PublishedMemoryService memory;

    public CanonPublicationController(
            CanonPublicationService publication, PublishedMemoryService memory) {
        this.publication = publication;
        this.memory = memory;
    }

    /**
     * 确认并发布指定正文，在同一事务中记录发布提交及记忆任务；重复发布同一有效正文可幂等返回。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param chapterNumber 目标章节号，从 1 开始
     * @param request 目标正文 ID、正文行版本、正史水位及待替换提交 ID
     */
    @PostMapping("/actions/publish")
    public CanonCommitResponse publish(
            @PathVariable UUID projectId,
            @PathVariable int chapterNumber,
            @RequestBody PublishManuscriptRequest request) {
        return publication.publish(projectId, chapterNumber, request);
    }

    /**
     * 读取本章当前有效发布对应的记忆状态与候选条目，不触发重新整理。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param chapterNumber 目标章节号，从 1 开始
     */
    @GetMapping("/memory")
    public PublishedMemoryService.View memory(
            @PathVariable UUID projectId, @PathVariable int chapterNumber) {
        return memory.status(projectId, chapterNumber);
    }

    public record RetryRequest(long version) {}

    /**
     * 显式启动失败、跳过或待核对的记忆任务；提交 ID 与记忆行版本必须匹配。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param chapterNumber 目标章节号，从 1 开始
     * @param commitId 本章当前有效发布提交 ID
     * @param request 待重试记忆任务的预期行版本
     */
    @PostMapping("/{commitId}/memory/actions/retry")
    public PublishedMemoryService.View retry(
            @PathVariable UUID projectId,
            @PathVariable int chapterNumber,
            @PathVariable UUID commitId,
            @RequestBody RetryRequest request) {
        return memory.retry(projectId, chapterNumber, commitId, request.version());
    }

    /**
     * 兼容旧版待消歧记忆的确认入口，只接受决定及实体引用调整，不允许改写原命题或证据。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param chapterNumber 目标章节号，从 1 开始
     * @param commitId 本章当前有效发布提交 ID
     * @param request 记忆预期行版本及全部原候选的确认决定与实体引用
     */
    @PostMapping("/{commitId}/memory/actions/confirm")
    public PublishedMemoryService.View confirm(
            @PathVariable UUID projectId,
            @PathVariable int chapterNumber,
            @PathVariable UUID commitId,
            @RequestBody PublishedMemoryService.Confirmation request) {
        return memory.confirm(projectId, chapterNumber, commitId, request);
    }

    /**
     * 保存作者对记忆的编辑，校验证据及实体引用后留存历史并更新投影，不改写已发布正文。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param chapterNumber 目标章节号，从 1 开始
     * @param commitId 本章当前有效发布提交 ID
     * @param request 记忆预期行版本及作者编辑后的完整候选列表
     */
    @PutMapping("/{commitId}/memory")
    public PublishedMemoryService.View saveMemory(
            @PathVariable UUID projectId,
            @PathVariable int chapterNumber,
            @PathVariable UUID commitId,
            @RequestBody PublishedMemoryService.Confirmation request) {
        return memory.save(projectId, chapterNumber, commitId, request);
    }

    /**
     * 读取当前有效发布的最近 20 份记忆修订快照，不恢复或应用历史内容。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param chapterNumber 目标章节号，从 1 开始
     * @param commitId 本章当前有效发布提交 ID
     */
    @GetMapping("/{commitId}/memory/history")
    public List<PublishedMemoryService.History> memoryHistory(
            @PathVariable UUID projectId,
            @PathVariable int chapterNumber,
            @PathVariable UUID commitId) {
        return memory.history(projectId, chapterNumber, commitId);
    }
}
