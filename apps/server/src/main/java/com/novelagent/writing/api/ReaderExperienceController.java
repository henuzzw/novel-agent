package com.novelagent.writing.api;

import com.novelagent.writing.application.ReaderExperienceService;
import com.novelagent.writing.domain.ReaderExperienceEntry;
import com.novelagent.writing.domain.ReaderExperienceManuscript;
import com.novelagent.writing.domain.ReaderExperienceMemory;
import com.novelagent.writing.domain.ReaderExperiencePlanInput;
import com.novelagent.writing.domain.ReaderExperienceSource;
import com.novelagent.writing.domain.ReaderExperienceSubmission;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 伏笔与承诺台账。
 *
 * <p>维护计划、查看可引用正文及提交作者确认的进展事件。计划与已发生状态分开；作者接受正文可以作证据，但不会因此自动提交正史。</p>
 */
@RestController
@RequestMapping("/api/v1/projects/{projectId}/reader-experiences")
public class ReaderExperienceController {
    private final ReaderExperienceService service;

    public ReaderExperienceController(ReaderExperienceService service) { this.service = service; }

    /**
     * 返回当前请求范围内的记录列表；项目或来源范围以传入标识及业务查询条件为准。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping
    public List<ReaderExperienceEntry> list(@PathVariable UUID projectId) { return service.list(projectId); }

    /**
     * 读取当前请求指定的业务记录或视图，不触发模型生成；缺失记录按本模块的返回或异常约定处理。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     */
    @GetMapping("/{id}")
    public ReaderExperienceEntry get(@PathVariable UUID projectId, @PathVariable UUID id) { return service.get(projectId, id); }

    /**
     * 列出可引用的作者已确认正文来源，并保留是否已提交正史及是否被替换的区别。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @return 符合本方法项目、来源及状态条件的结果；无匹配项时为空列表。
     */
    @GetMapping("/sources")
    public List<ReaderExperienceManuscript> sources(@PathVariable UUID projectId) { return service.sources(projectId); }

    /**
     * 读取原始或已确认来源资料并保留来源版本，供下载、证据引用或后续业务复核。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param manuscriptId 作为来源或证据引用的正文版本 ID。
     */
    @GetMapping("/sources/{manuscriptId}")
    public ReaderExperienceSource source(@PathVariable UUID projectId, @PathVariable UUID manuscriptId) {
        return service.source(projectId, manuscriptId);
    }

    /**
     * 按当前大纲分组已有有效正史摘要，保留未分配章节；不是新模型生成的全书压缩摘要。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     */
    @GetMapping("/memory")
    public ReaderExperienceMemory memory(@PathVariable UUID projectId) { return service.memory(projectId); }

    /**
     * 创建本模块业务记录或任务；是否继续执行、发布或确认由该模块后续动作决定。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param input 本次结构化业务输入或确认命令。
     */
    @PostMapping
    public ResponseEntity<ReaderExperienceEntry> create(@PathVariable UUID projectId, @RequestBody ReaderExperiencePlanInput input) {
        return ResponseEntity.status(201).body(service.create(projectId, input));
    }

    /**
     * 保存作者提交的编辑内容，并遵循当前业务状态及预期版本约束；不隐式触发模型重新生成。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param input 本次结构化业务输入或确认命令。
     */
    @PutMapping("/{id}")
    public ReaderExperienceEntry update(@PathVariable UUID projectId, @PathVariable UUID id, @RequestBody ReaderExperiencePlanInput input) {
        return service.update(projectId, id, input);
    }

    /**
     * 按预期版本及请求幂等标识软删除计划，保留历史来源与确认事件，不删除正文正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param expectedVersion 预期行版本，用于发现并发编辑或失效来源。
     * @param requestId 客户端请求关联或幂等 ID，具体用途见方法说明。
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID projectId, @PathVariable UUID id,
            @RequestParam long expectedVersion, @RequestParam UUID requestId) {
        service.delete(projectId, id, expectedVersion, requestId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 校验作者明确确认、合法状态转换和来源正文连续原文证据后追加台账事件；不会因此将正文提交正史。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param input 本次结构化业务输入或确认命令。
     */
    @PostMapping("/{id}/events")
    public ReaderExperienceEntry submit(@PathVariable UUID projectId, @PathVariable UUID id, @RequestBody ReaderExperienceSubmission input) {
        return service.submit(projectId, id, input);
    }
}
