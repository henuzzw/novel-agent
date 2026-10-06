package com.novelagent.writing.domain;

import java.util.HashSet;
import java.util.List;

public record FirstThreeChaptersContent(String summary, List<Assessment> assessments, List<Issue> issues) {
    public enum Dimension { FIRST_CHAPTER, CAUSAL_CONTINUITY, PAYOFF, REPETITION, CHARACTER, STYLE, LOGIC, SCENE }
    public enum Status { OBSERVATION, NOT_ASSESSED }
    public record Evidence(int chapterNumber, String quote) { }
    public record Assessment(Dimension dimension, Status status, String observation, List<Evidence> evidence) { }
    public record Issue(String id, Dimension dimension, String description, String suggestion, List<Evidence> evidence) { }

    public void validate(FirstThreeChaptersSource source, boolean local) {
        if (summary == null || summary.isBlank() || assessments == null || issues == null || issues.size() > 24)
            throw new IllegalArgumentException("三章报告结构不完整");
        var dimensions = new HashSet<Dimension>();
        for (var item : assessments) {
            if (item == null || item.dimension() == null || !dimensions.add(item.dimension()) || item.status() == null
                    || item.observation() == null || item.observation().isBlank() || item.evidence() == null)
                throw new IllegalArgumentException("三章报告维度无效");
            if ((local && item.status() != Status.NOT_ASSESSED)
                    || (item.status() == Status.OBSERVATION && item.evidence().isEmpty()))
                throw new IllegalArgumentException("报告判断缺少正文证据");
            evidence(item.evidence(), source);
        }
        if (dimensions.size() != Dimension.values().length) throw new IllegalArgumentException("报告缺少检查维度");
        var ids = new HashSet<String>();
        if (local && !issues.isEmpty()) throw new IllegalArgumentException("本地规则不作文学判断");
        for (var issue : issues) {
            if (issue == null || issue.id() == null || issue.id().isBlank() || !ids.add(issue.id())
                    || issue.dimension() == null || issue.description() == null || issue.description().isBlank()
                    || issue.suggestion() == null || issue.suggestion().isBlank()
                    || issue.evidence() == null || issue.evidence().isEmpty())
                throw new IllegalArgumentException("三章问题必须附正文证据和建议");
            evidence(issue.evidence(), source);
        }
    }
    private static void evidence(List<Evidence> values, FirstThreeChaptersSource source) {
        for (var value : values) {
            if (value == null || value.quote() == null || value.quote().isBlank() || value.quote().length() > 2000
                    || source.chapters().stream().noneMatch(c -> c.chapterNumber() == value.chapterNumber()
                    && c.body() != null && c.body().contains(value.quote())))
                throw new IllegalArgumentException("证据必须是对应章节正文中的连续原文");
        }
    }
}
