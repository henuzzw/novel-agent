package com.novelagent.chat.application;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.chat.domain.ChatSource;
import com.novelagent.planning.domain.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChatMaterialsTest {
    private final ObjectMapper json = new ObjectMapper();
    private ChatSource source(String fingerprint, String basis, boolean writable) {
        return new ChatSource("key", "title", "old", writable, fingerprint, basis, "PROFILE", "id", "background", json.createObjectNode());
    }
    @Test void checksActualRecordAndProjectBasisInsteadOfTrustingModelVersions() {
        assertThatCode(() -> ChatMaterials.checkCurrent(source("a", "b", true), source("a", "b", true))).doesNotThrowAnyException();
        assertThatThrownBy(() -> ChatMaterials.checkCurrent(source("a", "b", true), source("changed", "b", true))).hasMessageContaining("依据已变化");
        assertThatThrownBy(() -> ChatMaterials.checkCurrent(source("a", "b", true), source("a", "changed", true))).hasMessageContaining("依据已变化");
        assertThatThrownBy(() -> ChatMaterials.checkCurrent(source("a", "b", false), source("a", "b", true))).hasMessageContaining("不可修改");
    }
    @Test void chapterRevisionPreservesOpaqueTextAndMarksOnlyLaterPlansStale() {
        var chapters = List.of(new ChapterPlan(1, "开篇", "原开篇", ChapterPlanStatus.PLANNED),
                new ChapterPlan(2, "行动", "原行动", ChapterPlanStatus.PLANNED),
                new ChapterPlan(3, "既往", "不能改", ChapterPlanStatus.OCCURRED));
        var content = new OutlineContent("书", "核心", "结构", "节奏", 20000, 30000,
                List.of(new OutlineArc(1, "卷", "目标", "冲突", "转折", "结果", 20000, 30000, chapters)));
        var changed = ChatMaterials.patchOutline(json, content, "1", "新的完整文字块\n人物选择与代价。");
        var actual = changed.arcs().getFirst().chapters();
        assertThat(actual.get(0).content()).isEqualTo("新的完整文字块\n人物选择与代价。");
        assertThat(actual.get(0).contentNeedsUpdate()).isFalse();
        assertThat(actual.get(1).contentNeedsUpdate()).isTrue();
        assertThat(actual.get(2)).isEqualTo(chapters.get(2));
        assertThat(content.arcs().getFirst().chapters()).isEqualTo(chapters);
        assertThatThrownBy(() -> ChatMaterials.patchOutline(json, content, "3", "违规改动")).hasMessageContaining("已发生章节");
    }
}
