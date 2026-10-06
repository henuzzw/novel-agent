package com.novelagent.writing.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;

class ManuscriptLocalEditSelectionTest {
    @Test
    void replacesOnlyTheExplicitOccurrenceAndPreservesWhitespaceAndUnicode() {
        String body = "前文\r\n相同段落\n中间 😀\t相同段落\r\n结尾";
        var selection = ManuscriptLocalEditSelection.resolve(body, "相同段落", null, 2);
        String edited = selection.replace(body, "新段落");
        assertThat(edited.substring(0, selection.offset())).isEqualTo(body.substring(0, selection.offset()));
        assertThat(edited.substring(selection.offset() + 3)).isEqualTo(body.substring(selection.offset() + 4));
        assertThat(edited).isEqualTo("前文\r\n相同段落\n中间 😀\t新段落\r\n结尾");
    }

    @Test
    void rejectsMissingLocationWrongOffsetAndDisagreeingOccurrence() {
        assertThatThrownBy(() -> ManuscriptLocalEditSelection.resolve("aba aba", "aba", null, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManuscriptLocalEditSelection.resolve("aba aba", "aba", 4, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ManuscriptLocalEditSelection.resolve("aba aba", "ABA", 0, 1)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ManuscriptLocalEditSelection.resolve("aaaa", "aa", 1, 2).offset()).isEqualTo(1);
    }

    @Test
    void rejectsSurrogateSplitsNoOpAndChangedSource() {
        assertThatThrownBy(() -> ManuscriptLocalEditSelection.resolve("😀abc", "\uDE00", 1, null)).isInstanceOf(IllegalArgumentException.class);
        var selection = ManuscriptLocalEditSelection.resolve("abc", "b", 1, 1);
        assertThatThrownBy(() -> selection.replace("abc", "b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> selection.replace("adc", "new")).isInstanceOf(IllegalArgumentException.class);
        assertThat(selection.replace("abc", "")).isEqualTo("ac");
    }
}
