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
        assertThat(profile.narrativeVoice()).isNotBlank();
        assertThat(profile.dialogueStyle()).isNotBlank();
        assertThat(profile.craft().narratorPosition()).isNotBlank();
        assertThat(profile.avoidPatterns()).isNotEmpty();
    }

    @Test void renderedGuideRemainsBounded() throws Exception {
        var guide = WritingStyleGuide.render(seededProfile());
        assertThat(guide).isNotBlank();
        assertThat(guide.length()).isLessThan(7500);
    }
}
