package com.novelagent.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoryDirectionSetTest {

    @Test
    void selectsOnlyACandidateFromTheSameSet() {
        StoryDirectionCandidate first = candidate("关系成长线");
        StoryDirectionCandidate second = candidate("秘密调查线");
        StoryDirectionSet set = StoryDirectionSet.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                1,
                "TEST",
                null,
                snapshot(),
                List.of(first, second),
                List.of());

        set.select(first.id());

        assertThat(set.getStatus()).isEqualTo(StoryDirectionStatus.SELECTED);
        assertThat(set.getSelectedCandidateId()).isEqualTo(first.id());
        assertThatThrownBy(() -> set.select(UUID.randomUUID()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static StoryDirectionCandidate candidate(String title) {
        return new StoryDirectionCandidate(
                UUID.randomUUID(), title, "创意", "冲突", "弧光", "结构", "结局", "读者",
                List.of("优势"), List.of("风险"), List.of("特点"));
    }

    private static CreativeIntentSnapshot snapshot() {
        return new CreativeIntentSnapshot(
                "创意", List.of("悬疑"), null, "主角", "冲突", List.of("克制"), 120000,
                null, List.of(), List.of(), List.of(), 0);
    }
}
