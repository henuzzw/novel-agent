package com.novelagent.modelaccess.application;

import com.novelagent.planning.application.ModelProviderException;

/** Only sanitized diagnostics cross the API/task boundary, never raw token responses. */
public class ChatGptAccessException extends ModelProviderException {
    private final String category;
    public ChatGptAccessException(String category, String message) {
        super(message);
        this.category = category;
    }
    public String category() { return category; }
}
