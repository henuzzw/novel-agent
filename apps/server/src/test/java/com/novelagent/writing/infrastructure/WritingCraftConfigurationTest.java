package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;

class WritingCraftConfigurationTest {
    @Test void disabledConfigurationAddsNoCraftInstructions() {
        var disabled = new WritingCraftConfiguration(false);
        assertThat(disabled.manuscript()).isEmpty();
        assertThat(disabled.qualityReview()).isEmpty();
        assertThat(disabled.preview()).isEmpty();
    }

    @Test void enabledConfigurationUsesTheSharedStageRules() {
        var enabled = new WritingCraftConfiguration(true);
        assertThat(enabled.manuscript()).isEqualTo(WritingCraftRules.manuscript());
        assertThat(enabled.qualityReview()).isEqualTo(WritingCraftRules.qualityReview());
        assertThat(enabled.preview()).isEqualTo(WritingCraftRules.preview());
    }
}
