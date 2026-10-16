package com.novelagent.chat.domain;

import com.fasterxml.jackson.databind.JsonNode;

/** Frozen read target, including its complete record for optimistic apply and undo. Never sent as a writable tool. */
public record ChatSource(String key, String title, String text, boolean writable, String fingerprint,
        String basis, String kind, String recordId, String field, JsonNode snapshot) {
    public Summary summary() { return new Summary(key, title, writable, text.length()); }
    public record Summary(String key, String title, boolean writable, int characters) { }
}
