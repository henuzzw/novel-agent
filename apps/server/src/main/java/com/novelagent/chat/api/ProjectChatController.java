package com.novelagent.chat.api;

import com.novelagent.chat.application.*;
import com.novelagent.chat.domain.ChatSource;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

/**
 * 项目创作对话及作者决策接口。资料读取限定当前项目，修改候选必须由作者显式应用；模型工具不暴露应用接口。
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/chat")
public class ProjectChatController {
    private final ProjectChatService service;
    private final ProjectChatStore store;
    private final ChatMaterials materials;

    public ProjectChatController(
            ProjectChatService service, ProjectChatStore store, ChatMaterials materials) {
        this.service = service;
        this.store = store;
        this.materials = materials;
    }

    public record SendRequest(@NotNull UUID id, @NotBlank @Size(max = 8000) String question) {}

    /**
     * 读取项目对话历史及每轮执行状态，不调用模型。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     */
    @GetMapping("/turns")
    public List<ProjectChatStore.Turn> list(@PathVariable UUID projectId) {
        return store.list(projectId);
    }

    /**
     * 以请求 ID 创建或复用对话轮次并返回 202，模型与资料工具循环在后台执行。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param request 用于幂等创建对话的轮次 ID 及作者问题
     */
    @PostMapping("/turns")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ProjectChatStore.Turn send(
            @PathVariable UUID projectId, @Valid @RequestBody SendRequest request) {
        return service.send(projectId, request.id(), request.question());
    }

    /**
     * 停止指定对话轮次，阻止未完成候选保存；既有资料和已应用修改保留。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param id 目标记录或任务的稳定 ID
     */
    @PostMapping("/turns/{id}/stop")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void stop(@PathVariable UUID projectId, @PathVariable UUID id) {
        service.cancel(projectId, id);
    }

    /**
     * 返回当前项目可读取资料的目录摘要，不在列表中返回完整手稿。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     */
    @GetMapping("/sources")
    public List<ChatSource.Summary> sources(@PathVariable UUID projectId) {
        return materials.catalog(projectId).stream().map(ChatSource::summary).toList();
    }

    public record SourcePage(
            String key, String title, int offset, int end, int characters, String text) {}

    /**
     * 按字符偏移读取当前项目指定资料，每页最多 6000 个字符；拒绝越界偏移。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param key 当前项目资料目录中的授权资料键
     * @param offset 资料文本的起始字符偏移，范围为 0 至文本长度
     */
    @GetMapping("/source")
    public SourcePage read(
            @PathVariable UUID projectId,
            @RequestParam String key,
            @RequestParam(defaultValue = "0") int offset) {
        var source = materials.require(projectId, key);
        if (offset < 0 || offset > source.text().length())
            throw new IllegalArgumentException("资料偏移无效");
        int end = Math.min(source.text().length(), offset + 6000);
        return new SourcePage(
                key,
                source.title(),
                offset,
                end,
                source.text().length(),
                source.text().substring(offset, end));
    }

    /**
     * 读取对话产生的待确认及已处理修改候选，不自动应用。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     */
    @GetMapping("/changes")
    public List<ProjectChatStore.Change> changes(@PathVariable UUID projectId) {
        return store.changes(projectId);
    }

    /**
     * 处理作者对修改候选的应用、拒绝或撤销决定；存储用例继续校验来源版本与候选状态。
     *
     * @param projectId 所属小说项目 ID，访问范围由应用服务校验
     * @param id 待应用、拒绝或撤销的修改候选 ID
     * @param action 作者明确选择的候选处理动作
     */
    @PostMapping("/changes/{id}/{action}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void decide(
            @PathVariable UUID projectId, @PathVariable UUID id, @PathVariable String action) {
        store.decide(projectId, id, action);
    }
}
