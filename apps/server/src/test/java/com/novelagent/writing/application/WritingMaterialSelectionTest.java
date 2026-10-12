package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.StoryBibleContent;
import java.util.List;
import org.junit.jupiter.api.Test;

class WritingMaterialSelectionTest {
    @Test void onlyFiltersCharactersForFullyResolvedExplicitRoster() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        var a = json.readValue("{\"name\":\"江澈\",\"role\":\"PROTAGONIST\",\"identity\":\"学生\",\"coreDesire\":\"学习\"}",
                com.novelagent.planning.domain.CharacterBlueprint.class);
        var b = json.readValue("{\"name\":\"沈秋\",\"role\":\"SUPPORTING\",\"identity\":\"学生\",\"coreDesire\":\"学习\"}",
                com.novelagent.planning.domain.CharacterBlueprint.class);
        var c = json.readValue("{\"name\":\"许冬\",\"role\":\"SUPPORTING\",\"identity\":\"学生\",\"coreDesire\":\"学习\"}",
                com.novelagent.planning.domain.CharacterBlueprint.class);
        var source = new StoryBibleContent("核心", "主题", "校园", List.of(), "江澈", "", List.of(), List.of(),
                "冲突", "代价", "清爽", "", List.of(), List.of(), List.of(a, b, c));
        assertThat(WritingMaterialSelection.forChapter(source, "核心人物：江澈、沈秋\n本章正文").characterBlueprints()).containsExactly(a, b);
        assertThat(WritingMaterialSelection.forChapter(source, "沈秋与同学谈话").characterBlueprints()).containsExactly(a, b, c);
        assertThat(WritingMaterialSelection.forChapter(source, "核心人物：沈秋、班主任").characterBlueprints()).containsExactly(a, b, c);
    }
    @Test void selectsRequestWithoutMutatingSavedNotesOrGlobalRules() {
        var original = new StoryBibleContent("故事核心", "主题", "校园", List.of("不能擅自改成绩"),
                "江澈", "未来弧光", List.of("沈秋"), List.of("初始关系"), "矛盾", "代价", "清爽",
                "全书结局", List.of("必须保留约定"), List.of()).withDevelopmentNotes("完整九步底稿".repeat(1000));
        var selected = WritingMaterialSelection.forChapter(original, "江澈收到纸条");
        assertThat(selected.developmentNotes()).isNull();
        assertThat(selected.endingDirection()).isEmpty();
        assertThat(selected.worldRules()).isEqualTo(original.worldRules());
        assertThat(selected.hardConstraints()).isEqualTo(original.hardConstraints());
        assertThat(original.developmentNotes()).hasSizeGreaterThan(1000);
        var first = new ChapterPlan(1, "开头", "本章文字块", com.novelagent.planning.domain.ChapterPlanStatus.PLANNED);
        var second = new ChapterPlan(2, "未来", "不能提前兑现", com.novelagent.planning.domain.ChapterPlanStatus.PLANNED);
        var arc = new OutlineArc(1, "开端", "目标", "冲突", "未来转折", "未来结果", 4000, 6000, List.of(first, second));
        assertThat(WritingMaterialSelection.forChapter(arc, first).chapters()).containsExactly(first);
        assertThat(arc.chapters()).containsExactly(first, second);
    }
}
