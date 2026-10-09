package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.novelagent.writing.domain.WritingStyleProfile;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class WritingStyleGuideTest {
    @Test
    void maximumCustomProfileRemainsBoundedAndRenderingIsDeterministic() {
        var profile = new WritingStyleProfile("N".repeat(80), "V".repeat(600), "S".repeat(600),
                "D".repeat(600), "T".repeat(600), "E".repeat(600), "P".repeat(600),
                Collections.nCopies(12, "A".repeat(120)));
        var guide = WritingStyleGuide.render(profile);
        assertThat(guide.length()).isLessThan(8500);
        assertThat(WritingStyleGuide.render(profile)).isEqualTo(guide);
    }

    @Test
    void editedPresetKeepsAuthorValuesWithoutChangingTheOriginal() {
        var original = WritingStylePresets.all().getFirst();
        var edited = new WritingStyleProfile("Author style", "Author voice", original.sentenceRhythm(),
                original.descriptionFocus(), original.dialogueStyle(), original.emotionalExpression(),
                original.pacing(), original.avoidPatterns(), original.basePresetId(),
                original.basePresetVersion(), original.craft());
        var guide = WritingStyleGuide.render(edited);
        assertThat(guide).contains(edited.name(), edited.narrativeVoice());
        assertThat(edited.craft()).isSameAs(original.craft());
        assertThat(edited.basePresetId()).isEqualTo(original.basePresetId());
        assertThat(original.name()).isNotEqualTo(edited.name());
        assertThat(original.narrativeVoice()).isNotEqualTo(edited.narrativeVoice());
    }
}
