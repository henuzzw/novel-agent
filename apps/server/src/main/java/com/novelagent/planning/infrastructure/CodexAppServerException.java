package com.novelagent.planning.infrastructure;

import com.novelagent.planning.application.ModelProviderException;

public class CodexAppServerException extends ModelProviderException {

    private final Integer rpcCode;

    public CodexAppServerException(String message) {
        this(message, null, null);
    }

    public CodexAppServerException(String message, Throwable cause) {
        this(message, null, cause);
    }

    public CodexAppServerException(String message, Integer rpcCode) {
        this(message, rpcCode, null);
    }

    private CodexAppServerException(String message, Integer rpcCode, Throwable cause) {
        super(message, cause);
        this.rpcCode = rpcCode;
    }

    public boolean indicatesMissingThread() {
        String normalized = getMessage() == null ? "" : getMessage().toLowerCase();
        if (rpcCode == null) {
            return false;
        }
        boolean missingThread = normalized.contains("thread")
                && (normalized.contains("not found") || normalized.contains("unknown")
                || normalized.contains("archiv"));
        boolean missingRollout = (normalized.contains("no rollout found")
                || normalized.contains("rollout not found"))
                && normalized.contains("thread id");
        return missingThread || missingRollout;
    }
}
