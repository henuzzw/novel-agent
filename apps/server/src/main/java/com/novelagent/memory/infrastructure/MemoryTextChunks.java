package com.novelagent.memory.infrastructure;

import java.util.ArrayList;
import java.util.List;

/** Retrieval copies retain exact source offsets; no model summary or literary parsing. */
public final class MemoryTextChunks {
    private static final int MAX_CHARACTERS = 600;

    private MemoryTextChunks() { }

    public record Chunk(int ordinal, int start, int end, String content) { }

    public static List<Chunk> split(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<Chunk> result = new ArrayList<>();
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + MAX_CHARACTERS, text.length());
            if (end < text.length()) {
                int newline = text.lastIndexOf('\n', end - 1);
                if (newline >= start + MAX_CHARACTERS / 2) end = newline + 1;
                else {
                    for (int i = end - 1; i >= start + MAX_CHARACTERS / 2; i--) {
                        if ("。！？!?;；".indexOf(text.charAt(i)) >= 0) { end = i + 1; break; }
                    }
                }
                if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
            }
            result.add(new Chunk(result.size(), start, end, text.substring(start, end)));
            start = end;
        }
        return List.copyOf(result);
    }
}
