package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.application.CharacterNameService;
import com.novelagent.canon.application.CharacterProfileService;
import com.novelagent.memory.application.NovelMemoryContext;
import com.novelagent.planning.domain.CharacterBlueprintFixtures;
import com.novelagent.planning.domain.ChapterPlan;
import com.novelagent.planning.domain.OutlineArc;
import com.novelagent.planning.domain.OutlineWordBudget;
import com.novelagent.planning.infrastructure.OutlineModelPromptFactory;
import com.novelagent.writing.application.WritingStyleService;
import com.novelagent.writing.domain.ChapterContractContent;
import com.novelagent.writing.domain.ManuscriptContent;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CharacterBlueprintPromptTest {
    @Test void outlineReceivesFullVersionedBlueprintAndSeparatesOpeningFromFutureArc() {
        var bible = CharacterBlueprintFixtures.bible(List.of(CharacterBlueprintFixtures.character("江澈")));
        var factory = new OutlineModelPromptFactory(new ObjectMapper());
        var prompt = factory.userPrompt(bible, new OutlineWordBudget(6000, 1000, 16000, 1, 3, 2000, 1000, 3000), null, "不增加转学");
        assertThat(prompt).contains("characterBlueprints", "长期被要求懂事", "不公开他人的纸条", "不知道同学写了未送出的纸条");
        assertThat(factory.systemPrompt()).contains("不能每章重置回开篇", "未来弧光仅是计划", "有效正史矛盾时指出冲突");
    }

    @Test void contractManuscriptAndQualityReceiveBlueprintsWithoutReplacingIndependentProfiles() {
        var names = mock(CharacterNameService.class);
        var profiles = mock(CharacterProfileService.class);
        var styles = mock(WritingStyleService.class);
        when(names.render(any(), anyString())).thenAnswer(call -> call.getArgument(1));
        when(profiles.promptContext(any(), any(), any(), any(), any())).thenReturn("作者独立档案：不允许拍摄同学");
        when(styles.promptContext(any())).thenReturn("克制表达");
        var factory = new WritingPromptFactory(new ObjectMapper(), names, profiles, styles, null);
        var bible = CharacterBlueprintFixtures.bible(List.of(CharacterBlueprintFixtures.character("江澈")));
        var chapter = new ChapterPlan(1, "选座", "江澈", "约座", "挑选座位", "仍未表态", "如何面对", 1000, 2000);
        var arc = new OutlineArc(1, "开篇", "选择", "逃避", "约座", "留下期待", 3000, 6000, List.of(chapter));
        var contract = new ChapterContractContent("选座", "江澈", "约座", "开学", List.of("教室"),
                List.of("挑选座位"), List.of(), List.of("偷拍"), "留下期待", List.of(), "如何面对", 1000, 2000);
        var memory = new NovelMemoryContext(List.of(), List.of(), null);
        var prompts = List.of(factory.contract(UUID.randomUUID(), bible, arc, chapter, memory, null, ""),
                factory.manuscript(UUID.randomUUID(), bible, arc, chapter, contract, memory, ""),
                factory.qualityReview(UUID.randomUUID(), bible, contract,
                        new ManuscriptContent("选座", "他坐下来，仍未表态。", "选座", List.of()), memory, ""));
        for (var prompt : prompts) {
            assertThat(prompt.user()).contains("characterBlueprints", "长期被要求懂事", "作者独立档案：不允许拍摄同学");
            assertThat(prompt.system()).contains("不代表视角人物已知", "不能每章重置回开篇", "不自动覆盖或合并冲突");
        }
    }
}
