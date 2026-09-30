package com.novelagent.planning.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StoryBibleVersionTest {
    @Test
    void draftCanBeRevisedAndPublishedVersionIsLocked() {
        StoryBibleVersion version = StoryBibleVersion.create(UUID.randomUUID(), UUID.randomUUID(), 1,
                "TEST", null, UUID.randomUUID(), UUID.randomUUID(), content("初稿"));

        version.revise(content("修订稿"));
        version.publish();

        assertThat(version.getContent().logline()).isEqualTo("修订稿");
        assertThat(version.getStatus()).isEqualTo(StoryBibleStatus.PUBLISHED);
        assertThatThrownBy(() -> version.revise(content("再次修改")))
                .isInstanceOf(IllegalStateException.class);
    }

    private static StoryBibleContent content(String logline) {
        return new StoryBibleContent(logline, "主题", "世界", List.of("规则"), "主角", "弧光",
                List.of("配角"), List.of("关系"), "冲突", "代价", "文风", "结局", List.of(), List.of());
    }
}
