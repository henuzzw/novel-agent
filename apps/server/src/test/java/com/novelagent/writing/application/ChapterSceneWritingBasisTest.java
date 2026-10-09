package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.OutlineVersion;
import com.novelagent.planning.domain.StoryBibleVersion;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ChapterSceneWritingBasisTest {
    @Test
    void writingReusesTheSavedTextWithoutRegeneration() {
        var context = context(chapter().withSceneOutline("先停止传阅，再逐页返工。"));
        var basis = ChapterWritingBasisService.capture(context);
        assertThat(basis.plan().requiredBeats()).containsExactly("发现漏项", "【已保存场景底稿；未来规划不是正史】\n先停止传阅，再逐页返工。");
        assertThat(ChapterWritingBasisService.capture(context)).isEqualTo(basis);
    }

    @Test
    void staleScenesAreNotMandatoryBeatsAndStillAffectTheFingerprint() {
        var original = chapter().withSceneOutline("先停止传阅，再逐页返工。");
        var stale = new ChapterPlan(1, "返工", "江澈", "先收回版本", "发现漏项", "条件缺失", "未收回版本", 2000, 3000)
                .withSceneOutline(original.sceneOutline()).reviewScenesAgainst(original, false);
        var context = context(original);
        var changed = new WritingContextService.Context(context.outline(), context.bible(), context.arc(), stale);
        assertThat(ChapterWritingBasisService.capture(changed).plan().requiredBeats()).containsExactly("发现漏项");
        assertThat(changed.boundaryFingerprint()).isNotEqualTo(context.boundaryFingerprint());
        assertThat(changed.chapter().sceneOutline()).isEqualTo(original.sceneOutline());
    }

    @Test
    void emptyScenesDoNotBlockWritingOrCreateExtraPreparation() {
        assertThat(ChapterWritingBasisService.capture(context(chapter())).plan().requiredBeats()).containsExactly("发现漏项");
    }

    private static ChapterPlan chapter() {
        return new ChapterPlan(1, "返工", "江澈", "完成返工", "发现漏项", "条件缺失", "未收回版本", 2000, 3000);
    }

    private static WritingContextService.Context context(ChapterPlan chapter) {
        var arc = new OutlineArc(1, "第一幕", "目标", "冲突", "转折", "结果", 2000, 3000, List.of(chapter));
        var outline = mock(OutlineVersion.class);
        when(outline.getId()).thenReturn(UUID.randomUUID());
        when(outline.getContent()).thenReturn(new OutlineContent("书名", "前提", "结构", "节奏", 2000, 3000, List.of(arc)));
        return new WritingContextService.Context(outline, mock(StoryBibleVersion.class), arc, chapter);
    }
}
