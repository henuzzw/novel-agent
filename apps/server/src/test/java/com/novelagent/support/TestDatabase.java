package com.novelagent.support;

/** Connection settings shared by {@link DatabaseTest} classes; values come from the same DB_* variables as the app. */
public final class TestDatabase {
    private TestDatabase() {
    }

    public static String env(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value;
    }

    public static String url() {
        return "jdbc:postgresql://" + env("DB_HOST", "localhost") + ":" + env("DB_PORT", "5432")
                + "/" + env("DB_NAME", "novel_agent");
    }
}
