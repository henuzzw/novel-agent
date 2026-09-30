package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.novelagent.planning.application.GeneratedStoryDirections;
import com.novelagent.planning.domain.CreativeIntentSnapshot;
import java.util.List;
import org.junit.jupiter.api.Test;

class LocalStoryDirectionGeneratorTest {

    private static final java.util.UUID PROJECT_ID = java.util.UUID.randomUUID();
    private final LocalStoryDirectionGenerator generator = new LocalStoryDirectionGenerator();

    @Test
    void createsThreeStructurallyDifferentDirections() {
        CreativeIntentSnapshot intent = new CreativeIntentSnapshot(
                "一封迟到十年的信让女主重返故乡",
                List.of("悬疑", "成长"),
                "青年读者",
                "理性克制的档案修复师",
                "她必须在保护家人与揭开真相之间选择",
                List.of("克制", "温暖"),
                200000,
                "真相揭晓但保留余韵",
                List.of("钟楼"),
                List.of("无意义反转"),
                List.of("第三人称限知"),
                2);

        GeneratedStoryDirections result = generator.generate(PROJECT_ID, intent, List.of(), null);

        assertThat(result.generatorType()).isEqualTo("LOCAL_TEMPLATE");
        assertThat(result.directions()).hasSize(3);
        assertThat(result.directions()).extracting(direction -> direction.title()).doesNotHaveDuplicates();
        assertThat(result.questionsForAuthor()).isNotEmpty();
    }

    @Test
    void carriesTheAuthorsAdjustmentIntoEveryCandidate() {
        CreativeIntentSnapshot intent = new CreativeIntentSnapshot(
                "主角回到故乡", List.of(), null, "主角", "寻找真相", List.of(), 120000,
                null, List.of(), List.of(), List.of(), 0);

        GeneratedStoryDirections result = generator.generate(
                PROJECT_ID, intent, List.of(), "减少解谜，突出成长。");

        assertThat(result.directions())
                .allSatisfy(direction -> assertThat(direction.premise()).contains("减少解谜，突出成长"));
        assertThat(result.questionsForAuthor()).isEmpty();
    }
}
