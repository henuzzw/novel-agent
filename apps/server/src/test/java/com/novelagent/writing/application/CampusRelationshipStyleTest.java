package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.writing.domain.WritingStyleProfile;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CampusRelationshipStyleTest {
    static WritingStyleProfile seededProfile() throws Exception {
        try (var source = CampusRelationshipStyleTest.class.getResourceAsStream("/db/migration/V048__campus_relationship_style.sql")) {
            assertThat(source).isNotNull();
            var sql = new String(source.readAllBytes(), StandardCharsets.UTF_8);
            var parts = sql.split(java.util.regex.Pattern.quote("$style_seed$"), -1);
            assertThat(parts).hasSize(3);
            return new ObjectMapper().readValue(parts[1], WritingStyleProfile.class);
        }
    }

    @Test void migrationContainsCompleteVersionedPresetWithoutReplacingExistingStyles() throws Exception {
        var profile = seededProfile();
        assertThat(profile.name()).isEqualTo("校园关系：清爽叙事");
        assertThat(profile.basePresetId()).isEqualTo("campus-relationships");
        assertThat(profile.basePresetVersion()).isEqualTo(1);
        assertThat(profile.craft().examples()).hasSize(2);
        assertThat(profile.craft().evidence()).isEmpty();
        assertThat(profile.narrativeVoice()).contains("主体叙述朴素清楚", "少量机智与自嘲", "第三人称");
        assertThat(profile.dialogueStyle()).contains("人物各有声口", "不强加京腔");
        assertThat(profile.craft().narratorPosition()).contains("老舍", "张爱玲", "不声称模仿");
        assertThat(profile.avoidPatterns()).contains("照搬原作名句、人物和情节", "每段安排笑点或一句金句");
    }

    @Test void guideCarriesMechanicsIntoGenerationPreviewReviewAndRevision() throws Exception {
        var guide = WritingStyleGuide.render(seededProfile());
        assertThat(guide).contains("校园关系：清爽叙事", "campus-relationships", "目标、阻碍、回应、选择与后果",
                "道歉：", "高潮或强开篇", "不按显示名称猜测作者", "反例（不要模仿）", "检查标准：STYLE",
                "事实与硬约束优先", "不改事件顺序、知识、关系和结局", "不要先写中性稿再换词贴风格");
        assertThat(guide.length()).isLessThan(7500);
    }
}
