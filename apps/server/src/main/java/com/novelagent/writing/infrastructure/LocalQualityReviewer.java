package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.ManuscriptContent;
import com.novelagent.writing.domain.QualityDimension;
import com.novelagent.writing.domain.QualityReviewContent;
import com.novelagent.writing.domain.QualityScore;
import com.novelagent.writing.domain.ReviewIssue;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
class LocalQualityReviewer {
    private static final Pattern TRANSITIONS = Pattern.compile("然后|接着|随后|之后|后来");
    private static final Pattern REPEATED_PUNCTUATION = Pattern.compile("[，。；！？]{2,}");

    QualityReviewContent review(ManuscriptContent manuscript) {
        List<ReviewIssue> issues = new ArrayList<>();
        Set<String> paragraphs = new HashSet<>();
        for (String paragraph : manuscript.body().split("\\R+")) {
            String text = paragraph.strip();
            if (text.isEmpty()) continue;
            if (text.length() >= 12 && !paragraphs.add(text)) {
                add(issues, QualityDimension.STYLE, "出现完整重复段落", excerpt(text), "检查是否为重复粘贴；保留有作用的一处，避免重复解释。");
            }
            if (TRANSITIONS.matcher(text).results().count() >= 3) {
                add(issues, QualityDimension.SCENE, "段落连续使用时间连接词，可能只在罗列经过", excerpt(text),
                        "复核是否需要展开关键场景：保留原有事件和结果，呈现人物目标、阻力、动作或对话及状态变化。");
            }
            var punctuation = REPEATED_PUNCTUATION.matcher(text);
            if (punctuation.find()) {
                add(issues, QualityDimension.FLUENCY, "出现连续标点，建议复核是否为误输入", punctuation.group(), "检查连续标点是否符合此处语气，删除误输入的标点。");
            }
        }
        List<QualityScore> scores = Arrays.stream(QualityDimension.values())
                .map(dimension -> new QualityScore(dimension, null,
                        dimension == QualityDimension.LOGIC ? "本地规则不能判断因果与人物动机，请使用模型检查。"
                                : "本地仅做文本规则检查，不提供文学质量评分。"))
                .toList();
        return new QualityReviewContent("本地规则检查完成；未命中规则不代表文风、语句和逻辑已经合格。", scores, issues);
    }

    private static void add(List<ReviewIssue> issues, QualityDimension dimension, String description,
            String evidence, String suggestion) {
        if (issues.size() < 20) issues.add(new ReviewIssue("Q" + (issues.size() + 1), "INFO", dimension.name(),
                description, evidence, suggestion, false));
    }

    private static String excerpt(String text) {
        int length = Math.min(120, text.codePointCount(0, text.length()));
        return text.substring(0, text.offsetByCodePoints(0, length));
    }
}
