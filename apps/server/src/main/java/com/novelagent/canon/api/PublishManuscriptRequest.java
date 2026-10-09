package com.novelagent.canon.api;

import com.novelagent.planning.application.ModelProvider;
import java.util.UUID;

/** Explicit author publication; a replacement must name the current commit. */
public record PublishManuscriptRequest(UUID manuscriptVersionId, long expectedManuscriptVersion,
        long expectedCanonVersion, UUID expectedActiveCommitId, ModelProvider provider) { }
