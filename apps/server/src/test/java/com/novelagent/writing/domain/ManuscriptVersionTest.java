package com.novelagent.writing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManuscriptVersionTest {
    @Test
    void acceptedManuscriptBecomesImmutableWithoutBecomingCanon() {
        ManuscriptVersion version = ManuscriptVersion.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), 1, 1, "LOCAL_TEMPLATE", null, content("正文"));
        version.accept();
        assertThat(version.getStatus()).isEqualTo(ManuscriptStatus.AUTHOR_ACCEPTED);
        assertThatThrownBy(() -> version.revise(content("另一版")))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("不能修改");
    }

    private ManuscriptContent content(String body) {
        return new ManuscriptContent("第一章", body, "摘要", List.of("核对时间线"));
    }
}
