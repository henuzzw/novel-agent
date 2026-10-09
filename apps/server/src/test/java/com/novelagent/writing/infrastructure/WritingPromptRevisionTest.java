package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class WritingPromptRevisionTest {
    @Test
    void parsesManuscriptAndChangeSummary() {
        String output = """
                {"content":{"title":"Chapter","body":"Body","summary":"Summary","continuityNotes":[]},
                "changeSummary":["Revised dialogue"]}
                """;
        var result = new WritingModelOutputParser(new ObjectMapper()).manuscript(output);
        assertThat(result.content().body()).isEqualTo("Body");
        assertThat(result.changeSummary()).containsExactly("Revised dialogue");
    }
}
