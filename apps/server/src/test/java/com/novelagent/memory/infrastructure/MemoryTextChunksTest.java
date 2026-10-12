package com.novelagent.memory.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class MemoryTextChunksTest {
    @Test void reconstructsSourceExactlyIncludingWhitespaceAndSurrogatePairs() {
        String source = "第一段。\r\n\r\n" + "很长的原文，不能改写。".repeat(130) + "\ud83d\ude00结束\n ";
        var chunks = MemoryTextChunks.split(source);
        assertThat(chunks).hasSizeGreaterThan(2);
        assertThat(chunks.stream().map(MemoryTextChunks.Chunk::content).collect(Collectors.joining())).isEqualTo(source);
        for (int i = 0; i < chunks.size(); i++) {
            var chunk = chunks.get(i);
            assertThat(chunk.ordinal()).isEqualTo(i);
            assertThat(chunk.content()).isEqualTo(source.substring(chunk.start(), chunk.end())).hasSizeLessThanOrEqualTo(600);
            assertThat(Character.isHighSurrogate(chunk.content().charAt(chunk.content().length() - 1))).isFalse();
            assertThat(chunk.start()).isEqualTo(i == 0 ? 0 : chunks.get(i - 1).end());
        }
        assertThat(MemoryTextChunks.split(" \r\n")).isEmpty();
    }
}
