package com.novelagent.writing.application;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.domain.FirstThreeChaptersBudget;
import com.novelagent.writing.domain.FirstThreeChaptersContent;
import com.novelagent.writing.domain.FirstThreeChaptersReport;
import com.novelagent.writing.domain.FirstThreeChaptersSource;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 前三章连读检查。
 *
 * <p>获取完整三章与依赖来源，预算允许时事务外进行一次独立检查。保存前复核来源，不截断后假装完整连读，本地模板只检查规则覆盖。</p>
 */
@Service
public class FirstThreeChaptersService {
    private final FirstThreeChaptersStore store;
    private final FirstThreeChaptersModel model;
    public FirstThreeChaptersService(FirstThreeChaptersStore store, FirstThreeChaptersModel model) {
        this.store = store; this.model = model;
    }
    public record Report(UUID id, int versionNumber, String provider, String reviewMode, boolean current,
            String fingerprint, FirstThreeChaptersSource source, FirstThreeChaptersContent content,
            FirstThreeChaptersBudget budget, Instant createdAt) {
        static Report from(FirstThreeChaptersReport value, String fingerprint) {
            return new Report(value.getId(), value.getVersionNumber(), value.getProvider(), value.getReviewMode(),
                    value.getFingerprint().equals(fingerprint), value.getFingerprint(), value.getSource(),
                    value.getContent(), value.getBudget(), value.getCreatedAt());
        }
    }
    public record View(FirstThreeChaptersSource source, boolean available, FirstThreeChaptersBudget budget,
            Report latestReport, Report latestValidReport) { }
    /**
     * 读取完整三章与依赖来源、历史报告及保守预算，未调用模型；依据不足或预算超限明确显示不可检查。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param ids 作者显式选定的三章正文版本 ID。
     * @param provider 明确选择的生成供应商；本地模板不代表真实文学生成。
     * @param instruction 作者本次要求，只能在已有事实与授权边界内执行。
     */
    public View get(UUID projectId, List<UUID> ids, ModelProvider provider, String instruction) {
        var source = store.snapshot(projectId, selection(ids));
        var reports = store.latest(projectId, source.fingerprint());
        Report latest = reports.isEmpty() ? null : Report.from(reports.getFirst(), source.fingerprint());
        Report valid = source.available() ? reports.stream().filter(r -> r.getFingerprint().equals(source.fingerprint()))
                .findFirst().map(r -> Report.from(r, source.fingerprint())).orElse(null) : null;
        return new View(source, source.available(), model.budget(source, requireProvider(provider), instruction(instruction)), latest, valid);
    }
    /**
     * 核对作者提供的来源指纹和输入额度后，事务外通读完整三章一次，保存时重检来源；不裁剪后声称完整检查。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param ids 作者显式选定的三章正文版本 ID。
     * @param provider 明确选择的生成供应商；本地模板不代表真实文学生成。
     * @param instruction 作者本次要求，只能在已有事实与授权边界内执行。
     * @param expectedFingerprint 界面读取的来源指纹，防止模型使用已变化依据。
     * @param maxInputTokens 作者接受的最大输入预算，不是供应商实际 usage。
     */
    public Report check(UUID projectId, List<UUID> ids, ModelProvider provider, String instruction,
            String expectedFingerprint, int maxInputTokens) {
        var selection = selection(ids);
        var source = store.snapshot(projectId, selection);
        if (!source.available()) throw new IllegalArgumentException(String.join("；", source.unavailableReasons()));
        if (!source.fingerprint().equals(expectedFingerprint)) throw new IllegalStateException("来源已变化，请刷新三章与预算");
        provider = requireProvider(provider);
        instruction = instruction(instruction);
        var budget = model.budget(source, provider, instruction);
        if (maxInputTokens <= 0 || budget.estimatedInputTokens() > maxInputTokens || !budget.fits())
            throw new IllegalArgumentException("预算不足，无法完整检查三章；不会截断正文");
        var content = model.check(source, provider, instruction);
        content.validate(source, provider == ModelProvider.LOCAL_TEMPLATE);
        return Report.from(store.save(source, selection, provider.name(), instruction, content, budget), source.fingerprint());
    }
    private static ModelProvider requireProvider(ModelProvider value) {
        if (value == null) throw new IllegalArgumentException("请明确选择检查模型");
        return value;
    }
    private static String instruction(String value) {
        if (value != null && value.length() > 4000) throw new IllegalArgumentException("检查要求不能超过4000字");
        return value == null ? "" : value;
    }
    private static List<UUID> selection(List<UUID> value) {
        if (value == null || value.isEmpty()) return List.of();
        if (value.size() != 3 || value.stream().anyMatch(java.util.Objects::isNull)
                || value.stream().distinct().count() != 3) throw new IllegalArgumentException("请按章序选择三个不同正文版本");
        return List.copyOf(value);
    }
}
