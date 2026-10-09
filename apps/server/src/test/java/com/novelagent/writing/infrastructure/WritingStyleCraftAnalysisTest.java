package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProviderException;
import com.novelagent.writing.application.WritingStyleGuide;
import com.novelagent.writing.application.WritingStylePresets;
import com.novelagent.writing.domain.WritingStyleCraft;
import com.novelagent.writing.domain.WritingStyleProfile;
import java.util.List;
import org.junit.jupiter.api.Test;

class WritingStyleCraftAnalysisTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final WritingStyleCraft source = WritingStylePresets.all().getFirst().craft();

    private WritingStyleProfile analyzed(String quote) {
        var craft = new WritingStyleCraft(source.narratorPosition(), source.paragraphMoves(), source.sentenceMoves(),
                source.wordChoice(), source.dialogueMoves(), source.rhetoricMoves(), source.sceneVariants(),
                source.revisionChecks(), List.of(), List.of(new WritingStyleCraft.Evidence("paragraphMoves", quote,
                "问话后继续用动作推进，没有替角色解释所有情绪。")));
        var p = WritingStylePresets.all().getFirst();
        return new WritingStyleProfile("样本风格", p.narrativeVoice(), p.sentenceRhythm(), p.descriptionFocus(),
                p.dialogueStyle(), p.emotionalExpression(), p.pacing(), p.avoidPatterns(), null, null, craft);
    }

    @Test void acceptsExactSampleEvidenceWithoutCopyingQuotationIntoWritingGuide() throws Exception {
        var profile = analyzed("她拿起书包。");
        var parsed = new WritingModelOutputParser(mapper).analyzedStyle(mapper.writeValueAsString(profile), "她拿起书包。然后坐下。");
        assertThat(parsed).isEqualTo(profile);
        assertThat(WritingStyleGuide.render(parsed)).doesNotContain("她拿起书包。");
    }

    @Test void rejectsInventedEvidenceAndPretendedPresetIdentity() throws Exception {
        assertThatThrownBy(() -> new WritingModelOutputParser(mapper).analyzedStyle(
                mapper.writeValueAsString(analyzed("原文没有这句话")), "她拿起书包。"))
                .isInstanceOf(ModelProviderException.class);
        assertThatThrownBy(() -> new WritingModelOutputParser(mapper).analyzedStyle(
                mapper.writeValueAsString(WritingStylePresets.all().getFirst()), "她拿起书包。"))
                .isInstanceOf(ModelProviderException.class);
    }

    @Test void schemaBoundsMechanicsAndEvidence() {
        var schema = new WritingOutputSchemas(mapper).writingStyle();
        var fields = schema.path("properties").path("craft").path("properties");
        assertThat(fields.path("paragraphMoves").path("maxLength").asInt()).isEqualTo(1000);
        assertThat(fields.path("examples").path("maxItems").asInt()).isZero();
        assertThat(fields.path("evidence").path("minItems").asInt()).isEqualTo(1);
    }

    @Test void oldJsonStillDeserializesAndRejectsInvalidMetadataOrOversizedCraft() throws Exception {
        var json = mapper.valueToTree(WritingStylePresets.all().getFirst());
        ((com.fasterxml.jackson.databind.node.ObjectNode) json).remove(List.of("craft", "basePresetId", "basePresetVersion"));
        var legacy = mapper.treeToValue(json, WritingStyleProfile.class);
        assertThat(legacy.craft()).isNull();
        assertThatThrownBy(() -> new WritingStyleProfile(legacy.name(), legacy.narrativeVoice(), legacy.sentenceRhythm(),
                legacy.descriptionFocus(), legacy.dialogueStyle(), legacy.emotionalExpression(), legacy.pacing(),
                legacy.avoidPatterns(), "street-humor", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new WritingStyleCraft("字".repeat(1001), source.paragraphMoves(), source.sentenceMoves(),
                source.wordChoice(), source.dialogueMoves(), source.rhetoricMoves(), source.sceneVariants(), source.revisionChecks(),
                List.of(), List.of())).isInstanceOf(IllegalArgumentException.class);
    }
}
