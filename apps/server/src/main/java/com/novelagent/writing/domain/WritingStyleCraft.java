package com.novelagent.writing.domain;

import java.util.List;

public record WritingStyleCraft(String narratorPosition, String paragraphMoves, String sentenceMoves,
        String wordChoice, String dialogueMoves, String rhetoricMoves, String sceneVariants,
        String revisionChecks, List<Example> examples, List<Evidence> evidence) {
    public WritingStyleCraft {
        for (String value : new String[] {narratorPosition, paragraphMoves, sentenceMoves, wordChoice,
                dialogueMoves, rhetoricMoves, sceneVariants, revisionChecks}) require(value, 1000);
        examples = examples == null ? List.of() : List.copyOf(examples);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (examples.size() > 3 || evidence.size() > 6) throw new IllegalArgumentException("风格示例或依据数量超限");
    }

    public record Example(String scene, String facts, String positive, String nearMiss, String explanation) {
        public Example {
            require(scene, 80);
            require(facts, 500);
            require(positive, 1500);
            require(nearMiss, 1500);
            require(explanation, 600);
        }
    }

    public record Evidence(String dimension, String quote, String explanation) {
        public Evidence {
            if (!List.of("narratorPosition", "paragraphMoves", "sentenceMoves", "wordChoice", "dialogueMoves",
                    "rhetoricMoves", "sceneVariants", "revisionChecks").contains(dimension)) {
                throw new IllegalArgumentException("未知风格依据维度");
            }
            require(quote, 300);
            require(explanation, 600);
        }
    }

    public void requireEvidenceIn(String sample) {
        for (Evidence item : evidence) {
            if (!sample.contains(item.quote())) throw new IllegalArgumentException("风格依据不在样本文字中");
        }
    }

    private static void require(String value, int limit) {
        if (value == null || value.isBlank() || value.length() > limit) {
            throw new IllegalArgumentException("风格技法字段为空或超长");
        }
    }
}
