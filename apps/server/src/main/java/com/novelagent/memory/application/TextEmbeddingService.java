package com.novelagent.memory.application;

public interface TextEmbeddingService {
    int dimensions();

    String modelName();

    float[] embed(String text);
}
