package com.novelagent.ingest.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public final class ImportAnalysis {
    private ImportAnalysis() { }
    public record Chapter(UUID id, int ordinal, String title, String content) { }
    public record Evidence(UUID chapterId, String quote, int occurrence) {
        public Evidence {
            if (chapterId == null || occurrence < 0 || occurrence > 10000) invalid("原文证据位置无效");
            quote = text(quote, 1200, false);
        }
    }
    public record Item(String key, String category, String certainty, String title, String description,
            List<String> subjects, String progress, List<Evidence> evidence) {
        public Item {
            if (key == null || !key.matches("[A-Za-z0-9_-]{1,80}")) invalid("解析项标识无效");
            if (!Set.of("CHARACTER", "WORLD", "RELATIONSHIP", "EVENT", "CLUE", "FORESHADOW").contains(category == null ? "" : category)) invalid("解析类别无效");
            if (!Set.of("FACT", "INFERENCE", "UNKNOWN").contains(certainty == null ? "" : certainty)) invalid("解析依据类型无效");
            title = text(title, 200, false); description = text(description, 1800, false);
            subjects = subjects == null ? List.of() : List.copyOf(subjects);
            if (subjects.size() > 12) invalid("关联人物过多");
            subjects = subjects.stream().map(value -> text(value, 100, false)).toList();
            if (!Set.of("NOT_APPLICABLE", "SET_UP", "REINFORCED", "PAYOFF", "UNRESOLVED", "UNKNOWN").contains(progress == null ? "" : progress)) invalid("线索进度无效");
            if (!Set.of("CLUE", "FORESHADOW").contains(category) && !"NOT_APPLICABLE".equals(progress)) invalid("非线索项不能标记伏笔进度");
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
            if (evidence.size() > 6 || (!"UNKNOWN".equals(certainty) && evidence.isEmpty())) invalid("事实或推测必须附原文依据");
        }
    }
    public record Batch(String summary, List<Item> items) {
        public Batch {
            summary = text(summary, 3000, false); items = items == null ? List.of() : List.copyOf(items);
            if (items.size() > 80 || items.stream().map(Item::key).distinct().count() != items.size()) invalid("单段解析项过多或标识重复");
        }
    }
    public record Content(List<String> summaries, List<Item> items) {
        public Content { summaries = List.copyOf(summaries); items = List.copyOf(items); }
        public Content append(Batch batch, int index) {
            var merged = new ArrayList<>(items);
            for (var item : batch.items()) merged.add(new Item("b" + index + "_" + item.key(), item.category(), item.certainty(),
                    item.title(), item.description(), item.subjects(), item.progress(), item.evidence()));
            var notes = new ArrayList<>(summaries); notes.add(batch.summary());
            return new Content(notes, merged);
        }
    }
    public record Decision(String key, String action, String note) {
        public Decision {
            if (key == null || key.isBlank() || !Set.of("KEEP", "REWORK", "DROP").contains(action == null ? "" : action)) invalid("请逐项选择处理方式");
            note = text(note, 1000, true);
            if ("REWORK".equals(action) && note.isBlank()) invalid("重构项须填写改编要求");
        }
    }
    public static void validateDecisions(Content content, List<Decision> decisions, ImportPlanningMode mode) {
        if (decisions == null || mode == null) invalid("缺少解析确认信息");
        var keys = new HashSet<>(content.items().stream().map(Item::key).toList());
        if (decisions.size() != keys.size() || decisions.stream().map(Decision::key).distinct().count() != decisions.size()
                || !keys.equals(new HashSet<>(decisions.stream().map(Decision::key).toList()))) invalid("所有解析项必须逐项决定，不能遗漏或重复");
        if (mode == ImportPlanningMode.CONTINUE_MANUSCRIPT && decisions.stream().anyMatch(item -> item.action().equals("REWORK"))) invalid("续写不能重构原文；不采用解析结论也不授权修改原文事实");
    }
    public static int locate(Evidence evidence, String source) {
        int start = -1;
        for (int i = 0; i <= evidence.occurrence(); i++) {
            start = source.indexOf(evidence.quote(), start + 1);
            if (start < 0) invalid("原文证据不存在或出现序号不匹配");
        }
        return start;
    }
    public static void validateBatch(Batch batch, List<Slice> slices, List<Chapter> chapters) {
        for (var item : batch.items()) for (var evidence : item.evidence()) {
            var chapter = chapters.stream().filter(value -> value.id().equals(evidence.chapterId())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("证据引用未知章节"));
            int start = locate(evidence, chapter.content());
            if (slices.stream().noneMatch(slice -> slice.chapterId().equals(evidence.chapterId()) && start >= slice.start()
                    && start + evidence.quote().length() <= slice.end())) invalid("证据超出本次实际读取范围");
        }
    }
    public record Slice(UUID chapterId, int ordinal, String title, int start, int end) { }
    public static Batch normalizeBatch(Batch batch, Slice slice, List<Chapter> chapters) {
        var chapter = chapters.stream().filter(value -> value.id().equals(slice.chapterId())).findFirst().orElseThrow();
        String fragment = chapter.content().substring(slice.start(), slice.end());
        var items = new ArrayList<Item>();
        for (int i = 0; i < batch.items().size(); i++) {
            var item = batch.items().get(i);
            var evidence = new ArrayList<Evidence>();
            for (int j = 0; j < item.evidence().size(); j++) {
                var value = item.evidence().get(j);
                try {
                    if (!value.chapterId().equals(slice.chapterId())) invalid("证据引用了本次未提供的章节");
                    int first = fragment.indexOf(value.quote());
                    if (first < 0) invalid("原文证据不存在，必须使用本段连续逐字引文");
                    int second = fragment.indexOf(value.quote(), first + 1);
                    // A unique literal match needs no model-supplied ordinal. Repeated quotes remain strict.
                    int offset = slice.start() + (second < 0 ? first : locate(value, fragment));
                    int occurrence = 0, found = chapter.content().indexOf(value.quote());
                    while (found >= 0 && found < offset) { occurrence++; found = chapter.content().indexOf(value.quote(), found + 1); }
                    evidence.add(new Evidence(value.chapterId(), value.quote(), occurrence));
                } catch (IllegalArgumentException error) {
                    throw new IllegalArgumentException("原文解析输出校验失败：items[" + i + "].evidence[" + j + "]：" + error.getMessage(), error);
                }
            }
            items.add(new Item(item.key(), item.category(), item.certainty(), item.title(), item.description(), item.subjects(), item.progress(), evidence));
        }
        var normalized = new Batch(batch.summary(), items); validateBatch(normalized, List.of(slice), chapters); return normalized;
    }
    public static List<Slice> slices(List<Chapter> chapters) {
        var result = new ArrayList<Slice>();
        for (var chapter : chapters) {
            for (int start = 0; start < chapter.content().length();) {
                int end = Math.min(start + 16000, chapter.content().length());
                if (end < chapter.content().length() && Character.isHighSurrogate(chapter.content().charAt(end - 1))) end--;
                result.add(new Slice(chapter.id(), chapter.ordinal(), chapter.title(), start, end)); start = end;
            }
        }
        if (result.isEmpty() || result.size() > 40) invalid("全文解析支持最多 40 段，每段 16000 字符；请拆分过大的导入文件");
        return List.copyOf(result);
    }
    private static String text(String value, int limit, boolean empty) {
        if (value == null) value = "";
        if (value.length() > limit || (!empty && value.isBlank())) invalid("解析字段为空或超过长度限制");
        return value;
    }
    private static void invalid(String message) { throw new IllegalArgumentException(message); }
}
