package com.novelagent.ingest.application;

import com.novelagent.ingest.infrastructure.ImportAnalysisPrompt;
import com.novelagent.planning.infrastructure.CodexSessionPolicy;
import com.novelagent.planning.infrastructure.StructuredModelGateway;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Service;

/**
 * 原文分段解析。
 *
 * <p>每次认领一段，在事务外调用模型，再由存储层校验原文证据并保存结果。失败保留任务状态，显式恢复后才能继续，不自动重试付费调用。</p>
 */
@Service
public class ImportAnalysisRunner {
    private final ImportAnalysisStore store;
    private final StructuredModelGateway models;
    private final ImportAnalysisPrompt prompt;
    private com.novelagent.project.application.BookTitleService titles;
    @org.springframework.beans.factory.annotation.Autowired
    public void setTitles(com.novelagent.project.application.BookTitleService titles) { this.titles = titles; }
    public ImportAnalysisRunner(ImportAnalysisStore store, StructuredModelGateway models, ImportAnalysisPrompt prompt) {
        this.store = store; this.models = models; this.prompt = prompt;
    }
    /**
     * 执行当前任务的下一个待处理阶段或分段；认领、来源复核及结果保存由专责存储服务控制。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param importId 导入文件记录 ID，必须与指定项目匹配。
     * @param id 当前方法所操作记录的稳定 ID。
     * @param version 本次操作要求匹配的业务行版本。
     */
    public ImportAnalysisStore.View next(UUID projectId, UUID importId, UUID id, long version) {
        var claim = store.claim(projectId, importId, id, version);
        try {
            if (titles != null) titles.generateIfNeeded(projectId, importId, claim.report().provider());
            var result = new AtomicReference<ImportAnalysisStore.View>();
            models.request(projectId, "IMPORT_SOURCE_ANALYSIS", claim.report().provider(), ImportAnalysisPrompt.SYSTEM,
                    claim.input(), prompt.schema(), "import_source_analysis", 12000, CodexSessionPolicy.NEW_THREAD,
                    raw -> result.set(store.finish(claim, raw)));
            return result.get();
        } catch (RuntimeException error) {
            store.fail(claim, error); return store.get(projectId, importId, id);
        }
    }
}
