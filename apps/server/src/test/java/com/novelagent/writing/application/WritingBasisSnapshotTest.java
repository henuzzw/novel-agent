package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.StoryBibleVersion;
import com.novelagent.project.domain.CreativeStrategy;
import com.novelagent.project.domain.CreativeStrategyPolicy;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WritingBasisSnapshotTest {
    @Test
    void capturesImmutableFingerprintBeforeEntitiesOrStrategyChange() {
        var outline = mock(OutlineVersion.class);
        var bible = mock(StoryBibleVersion.class);
        when(outline.getId()).thenReturn(UUID.randomUUID());
        when(bible.getId()).thenReturn(UUID.randomUUID());
        var context = new WritingContextService.Context(outline, bible, mock(OutlineArc.class), mock(ChapterPlan.class));
        var snapshot = WritingBasisSnapshot.capture(context);
        snapshot.requireUnchanged(context);
        var changedPolicy = new WritingContextService.Context(outline, bible, context.arc(), context.chapter(),
                null, null, CreativeStrategyPolicy.of(CreativeStrategy.FANQIE_GRIPPING));
        assertThatThrownBy(() -> snapshot.requireUnchanged(changedPolicy)).isInstanceOf(IllegalStateException.class);
        var changedPreparation = new WritingContextService.Context(outline, bible, context.arc(), context.chapter(),
                null, null, context.creativeStrategy(), "新确认的剧情单元");
        assertThatThrownBy(() -> snapshot.requireUnchanged(changedPreparation)).isInstanceOf(IllegalStateException.class);
        when(bible.getRowVersion()).thenReturn(1L);
        assertThatThrownBy(() -> snapshot.requireUnchanged(context)).isInstanceOf(IllegalStateException.class);
    }
}
