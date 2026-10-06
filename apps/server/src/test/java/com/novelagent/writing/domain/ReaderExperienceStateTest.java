package com.novelagent.writing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReaderExperienceStateTest {
    @Test void requiresSetupBeforePayoffAndKeepsAuthorEndingChoices() {
        assertThatThrownBy(() -> ReaderExperienceState.PLANNED.requireTransition(ReaderExperienceState.PAYOFF, false))
                .isInstanceOf(IllegalArgumentException.class);
        ReaderExperienceState.PLANNED.requireTransition(ReaderExperienceState.SET_UP, false);
        ReaderExperienceState.SET_UP.requireTransition(ReaderExperienceState.REINFORCED, false);
        ReaderExperienceState.REINFORCED.requireTransition(ReaderExperienceState.PAYOFF, false);
        ReaderExperienceState.PLANNED.requireTransition(ReaderExperienceState.ABANDONED, false);
        ReaderExperienceState.SET_UP.requireTransition(ReaderExperienceState.OPEN, false);
        ReaderExperienceState.OPEN.requireTransition(ReaderExperienceState.PAYOFF, false);
        assertThatThrownBy(() -> ReaderExperienceState.PAYOFF.requireTransition(ReaderExperienceState.SET_UP, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReaderExperienceState.ABANDONED.requireTransition(ReaderExperienceState.PAYOFF, false))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ReaderExperienceState.SET_UP.requireTransition(ReaderExperienceState.PLANNED, true))
                .isInstanceOf(IllegalArgumentException.class);
        ReaderExperienceState.PAYOFF.requireTransition(ReaderExperienceState.PAYOFF, true);
    }

    @Test void exactAuthorAcceptedEvidenceIsRequiredAndNonCanonStaysSeparate() {
        UUID project = UUID.randomUUID(), id = UUID.randomUUID();
        var source = new ReaderExperienceSource(id, project, 3, 1, ManuscriptStatus.AUTHOR_ACCEPTED,
                "她把纸条交给我，我认出了签名。", null, null, null, false);
        source.requireEvidence(project, 3, "我认出了签名");
        assertThat(source.canon()).isFalse();
        for (String quote : new String[]{"她把纸条…我认出了签名", "他把纸条交给我", " "}) {
            assertThatThrownBy(() -> source.requireEvidence(project, 3, quote)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> source.requireEvidence(UUID.randomUUID(), 3, "我认出了签名")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> source.requireEvidence(project, 2, "我认出了签名")).isInstanceOf(IllegalArgumentException.class);
        var draft = new ReaderExperienceSource(id, project, 3, 1, ManuscriptStatus.DRAFT, source.body(), null, null, null, false);
        assertThatThrownBy(() -> draft.requireEvidence(project, 3, "我认出了签名")).hasMessageContaining("AUTHOR_ACCEPTED");
    }

    @Test void sourceReplacementAndVersionChangesExpireEvidenceWithoutChangingItsState() {
        UUID project = UUID.randomUUID(), id = UUID.randomUUID(), oldCommit = UUID.randomUUID();
        var changed = new ReaderExperienceSource(id, project, 4, 1, ManuscriptStatus.AUTHOR_ACCEPTED,
                "纸条", oldCommit, id, 1L, false);
        assertThat(changed.staleReason(3, oldCommit, true, "纸条")).contains("正文已变化");
        var replaced = new ReaderExperienceSource(id, project, 3, 1, ManuscriptStatus.AUTHOR_ACCEPTED,
                "纸条", UUID.randomUUID(), UUID.randomUUID(), 2L, true);
        assertThat(replaced.staleReason(3, oldCommit, true, "纸条")).contains("正史已被替换");
        assertThatThrownBy(() -> replaced.requireEvidence(project, 3, "纸条")).hasMessageContaining("正史已被替换");
        var committed = new ReaderExperienceSource(id, project, 3, 1, ManuscriptStatus.AUTHOR_ACCEPTED,
                "纸条", oldCommit, id, 1L, false);
        assertThat(committed.staleReason(3, null, false, "纸条")).isNull();
        assertThat(committed.canon()).isTrue();
    }
}
