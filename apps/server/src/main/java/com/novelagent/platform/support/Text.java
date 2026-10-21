package com.novelagent.platform.support;

/** Null-safe text checks shared by domain validation, persistence mapping and prompt assembly. */
public final class Text {
    private Text() {
    }

    public static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public static boolean hasText(String value) {
        return !isBlank(value);
    }

    /** Optional author input: blank becomes {@code null}, anything else is trimmed. */
    public static String trimToNull(String value) {
        return isBlank(value) ? null : value.trim();
    }

    /** Returns the value unchanged, or rejects a missing field with {@code message}. */
    public static String requireText(String value, String message) {
        if (isBlank(value)) {
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
