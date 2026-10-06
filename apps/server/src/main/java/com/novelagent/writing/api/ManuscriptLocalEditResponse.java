package com.novelagent.writing.api;

import java.util.UUID;

public record ManuscriptLocalEditResponse(String assessment, String message, UUID sourceManuscriptId,
        long sourceRowVersion, String selection, int occurrence, int offset, String replacement,
        ManuscriptResponse manuscript) {
}
