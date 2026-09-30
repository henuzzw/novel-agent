package com.novelagent.writing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChapterContractVersionTest {
    @Test
    void approvedContractBecomesImmutable() {
        ChapterContractVersion version = ChapterContractVersion.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, 1, "LOCAL_TEMPLATE", null, content("初稿"));
        version.approve();
        assertThat(version.getStatus()).isEqualTo(ChapterContractStatus.APPROVED);
        assertThatThrownBy(() -> version.revise(content("修改")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不能修改");
    }

    private ChapterContractContent content(String title) {
        return new ChapterContractContent(title, "女主", "找到线索", "清晨", List.of("教室"),
                List.of("发生冲突"), List.of("发现真相"), List.of("不提前揭底"), "决定行动",
                List.of("留下纸条"), "门外传来脚步声", 2400, 3600);
    }
}
