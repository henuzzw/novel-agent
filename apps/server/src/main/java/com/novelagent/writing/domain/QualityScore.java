package com.novelagent.writing.domain;

public record QualityScore(QualityDimension dimension, Integer score, String rationale) {
}
