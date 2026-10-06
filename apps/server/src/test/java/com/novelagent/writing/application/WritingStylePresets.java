package com.novelagent.writing.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.writing.domain.WritingStyleProfile;
import java.io.IOException;
import java.util.List;

// Offline fixtures only. Production presets are read from writing_style_preset.
public final class WritingStylePresets {
    private WritingStylePresets() { }

    public static List<WritingStyleProfile> all() {
        try (var input = WritingStylePresets.class.getResourceAsStream("/writing/style-presets-v1.json")) {
            return new ObjectMapper().readValue(input, new TypeReference<>() { });
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
