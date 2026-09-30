package com.novelagent.planning.domain;

import java.util.List;
import java.util.UUID;

public record StoryDirectionCandidate(
        UUID id,
        String title,
        String premise,
        String centralConflict,
        String protagonistArc,
        String structure,
        String endingDirection,
        String audienceFit,
        List<String> strengths,
        List<String> risks,
        List<String> distinctiveFeatures) {
}
