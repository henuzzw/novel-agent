package com.novelagent.project.application;

public class ResourceVersionConflictException extends RuntimeException {

    private final long expectedVersion;
    private final long actualVersion;

    public ResourceVersionConflictException(long expectedVersion, long actualVersion) {
        super("Expected version " + expectedVersion + " but current version is " + actualVersion);
        this.expectedVersion = expectedVersion;
        this.actualVersion = actualVersion;
    }

    /** Rejects a stale write: the caller's expected row version must equal the stored one. */
    public static void checkVersion(long expected, long actual) {
        if (expected != actual) {
            throw new ResourceVersionConflictException(expected, actual);
        }
    }

    public long getExpectedVersion() {
        return expectedVersion;
    }

    public long getActualVersion() {
        return actualVersion;
    }
}

