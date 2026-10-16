package com.novelagent.chat.domain;

/** Provider-neutral server tool envelope, decoded internally from the existing plain-text protocol. */
public record ChatAction(String action, String source, int offset, String query, String replacement,
        String reason, String impact, String reply) { }
