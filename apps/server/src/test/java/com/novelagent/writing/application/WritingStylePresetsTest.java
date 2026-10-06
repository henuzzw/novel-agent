package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.novelagent.writing.domain.WritingStyleProfile;
import java.util.HashSet;
import org.junit.jupiter.api.Test;

class WritingStylePresetsTest {
    @Test
    void retainsExistingPresetsAndAddsSixDistinctAuthorReferences() {
        var presets = WritingStylePresets.all();
        assertThat(presets).hasSize(11);
        assertThat(presets.subList(0, 5)).extracting(WritingStyleProfile::name)
                .containsExactly("现实细腻", "轻快口语", "悬疑克制", "诗性抒情", "紧凑有力");
        assertThat(presets.subList(5, 11)).extracting(WritingStyleProfile::name)
                .containsExactly("鲁迅参考：冷峻反讽", "老舍参考：市井幽默", "钱钟书参考：机智比喻",
                        "汪曾祺参考：清淡烟火", "王小波参考：智性幽默", "余华参考：平实叙事");
        assertThat(new HashSet<>(presets.stream().map(WritingStyleProfile::name).toList())).hasSize(11);
        assertThat(new HashSet<>(presets.stream().map(WritingStyleProfile::narrativeVoice).toList())).hasSize(11);
        assertThat(presets.subList(5, 11)).allSatisfy(profile -> {
            assertThat(profile.avoidPatterns()).contains("照搬原作名句、人物和情节");
            assertThat(profile.pacing()).isNotBlank();
            assertThat(profile.dialogueStyle()).isNotBlank();
        });
    }

    @Test
    void protectsIdentityKnowledgeWorldRulesAndEventsAcrossDistinctStyles() {
        var presets = WritingStylePresets.all();
        assertThat(presets.get(6).dialogueStyle()).contains("现有籍贯、时代与身份", "不强加京腔");
        assertThat(presets.get(7).avoidPatterns()).contains("越过视角人物的知识边界");
        assertThat(presets.get(8).pacing()).contains("章节目标和必要冲突");
        assertThat(presets.get(9).avoidPatterns()).contains("为了荒诞改动世界规则和既有事实");
        assertThat(presets.get(10).avoidPatterns()).contains("为了模仿而新增苦难、死亡或暴力");
        assertThatThrownBy(() -> presets.get(5).avoidPatterns().add("新规则"))
                .isInstanceOf(UnsupportedOperationException.class);
    }
}
