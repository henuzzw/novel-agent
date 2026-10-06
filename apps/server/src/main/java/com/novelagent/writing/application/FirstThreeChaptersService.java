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
    public View get(UUID projectId, List<UUID> ids, ModelProvider provider, String instruction) {
        var source = store.snapshot(projectId, selection(ids));
        var reports = store.latest(projectId, source.fingerprint());
        Report latest = reports.isEmpty() ? null : Report.from(reports.getFirst(), source.fingerprint());
        Report valid = source.available() ? reports.stream().filter(r -> r.getFingerprint().equals(source.fingerprint()))
                .findFirst().map(r -> Report.from(r, source.fingerprint())).orElse(null) : null;
        return new View(source, source.available(), model.budget(source, requireProvider(provider), instruction(instruction)), latest, valid);
    }
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
