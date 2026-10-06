package com.novelagent.writing.domain;

import com.novelagent.planning.domain.StoryBibleContent;
import java.util.List;

public record WritingStyleRecommendationContent(String summary, List<Recommendation> recommendations) {
    public WritingStyleRecommendationContent {
        requireText(summary, 1000, "风格推荐摘要");
        if (recommendations == null || recommendations.size() > 3) {
            throw new IllegalArgumentException("风格推荐最多三项");
        }
        recommendations = List.copyOf(recommendations);
        if (recommendations.stream().map(Recommendation::presetName).distinct().count() != recommendations.size()) {
            throw new IllegalArgumentException("风格推荐不能重复");
        }
    }

    public void requireEvidenceIn(StoryBibleContent bible) {
        for (Recommendation recommendation : recommendations) {
            for (Evidence evidence : recommendation.evidence()) {
                String source = switch (evidence.field()) {
                    case "logline" -> bible.logline();
                    case "theme" -> bible.theme();
                    case "worldSetting" -> bible.worldSetting();
                    case "protagonist" -> bible.protagonist();
                    case "protagonistArc" -> bible.protagonistArc();
                    case "centralConflict" -> bible.centralConflict();
                    case "stakes" -> bible.stakes();
                    case "narrativeStyle" -> bible.narrativeStyle();
                    case "endingDirection" -> bible.endingDirection();
                    default -> throw new IllegalArgumentException("风格推荐引用了未知圣经字段");
                };
                if (source == null || !source.contains(evidence.quote())) {
                    throw new IllegalArgumentException("风格推荐证据不在对应的故事圣经字段中");
                }
            }
        }
    }

    public record Recommendation(String presetName, String reason, String tradeoff, List<Evidence> evidence) {
        public Recommendation {
            requireText(presetName, 80, "推荐预设名称");
            requireText(reason, 800, "推荐理由");
            requireText(tradeoff, 600, "风格取舍");
            if (evidence == null || evidence.isEmpty() || evidence.size() > 3) {
                throw new IllegalArgumentException("每项风格推荐需要一至三条圣经证据");
            }
            evidence = List.copyOf(evidence);
        }
    }

    public record Evidence(String field, String quote) {
        public Evidence {
            requireText(field, 40, "圣经证据字段");
            requireText(quote, 300, "圣经证据原文");
        }
    }

    private static void requireText(String value, int limit, String label) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException(label + "不能为空且不能超过 " + limit + " 字符");
        }
    }
}
