package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.ChapterPlanStatus;
import com.novelagent.planning.domain.CharacterBlueprint;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineContent;
import com.novelagent.planning.domain.StoryBibleContent;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class WritingMaterialSelectionTest {
    @Test void openingKeepsConstraintsAndPromisesButExcludesLaterDetailedChaptersAndRawNotes() {
        var source = new StoryBibleContent("核心", "主题", "校园", List.of("规则"), "主角", "弧光",
                List.of("配角"), List.of("关系"), "冲突", "代价", "风格", "结局", List.of("硬约束"), List.of())
                .withDevelopmentNotes("完整九步底稿".repeat(10000));
        assertThat(WritingMaterialSelection.forOpening(source)).isEqualTo(source.withDevelopmentNotes(null));
        assertThat(source.developmentNotes()).hasSize(60000);
        var chapters = IntStream.rangeClosed(1, 17).mapToObj(number ->
                new ChapterPlan(number, "章" + number, "完整计划" + number,
                        ChapterPlanStatus.PLANNED)).toList();
        var outline = new OutlineContent("书名", "核心", "全书方向", "节奏", 40000, 50000,
                List.of(new OutlineArc(1, "阶段", "目标", "冲突", "转折", "结果", 40000, 50000, chapters)));
        var selected = WritingMaterialSelection.forOpening(outline);
        assertThat(selected.arcs().getFirst().chapters()).containsExactlyElementsOf(chapters.subList(0, 3));
        assertThat(selected.structureSummary()).isEqualTo(outline.structureSummary());
        assertThat(outline.chapterCount()).isEqualTo(17);
    }
    @Test void onlyFiltersCharactersForFullyResolvedExplicitRoster() throws Exception {
        var json = new ObjectMapper();
        var a = json.readValue("{\"name\":\"江澈\",\"role\":\"PROTAGONIST\",\"identity\":\"学生\",\"coreDesire\":\"学习\"}",
                CharacterBlueprint.class);
        var b = json.readValue("{\"name\":\"沈秋\",\"role\":\"SUPPORTING\",\"identity\":\"学生\",\"coreDesire\":\"学习\"}",
                CharacterBlueprint.class);
        var c = json.readValue("{\"name\":\"许冬\",\"role\":\"SUPPORTING\",\"identity\":\"学生\",\"coreDesire\":\"学习\"}",
                CharacterBlueprint.class);
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
        var first = new ChapterPlan(1, "开头", "本章文字块", ChapterPlanStatus.PLANNED);
        var second = new ChapterPlan(2, "未来", "不能提前兑现", ChapterPlanStatus.PLANNED);
        var arc = new OutlineArc(1, "开端", "目标", "冲突", "未来转折", "未来结果", 4000, 6000, List.of(first, second));
        assertThat(WritingMaterialSelection.forChapter(arc, first).chapters()).containsExactly(first);
        assertThat(arc.chapters()).containsExactly(first, second);
    }
}
