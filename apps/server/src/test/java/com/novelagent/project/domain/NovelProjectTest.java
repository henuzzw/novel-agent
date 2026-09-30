package com.novelagent.project.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class NovelProjectTest {

    @Test
    void createsAnActiveProjectWithCanonVersionZero() {
        NovelProject project = NovelProject.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "  钟楼来信  ",
                EntryMode.IDEA);

        assertThat(project.getName()).isEqualTo("钟楼来信");
        assertThat(project.getStatus()).isEqualTo(ProjectStatus.ACTIVE);
        assertThat(project.getCurrentCanonVersion()).isZero();
    }
}
