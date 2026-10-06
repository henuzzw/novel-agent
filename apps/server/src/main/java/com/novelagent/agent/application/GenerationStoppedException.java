package com.novelagent.agent.application;

public class GenerationStoppedException extends RuntimeException {
    public GenerationStoppedException() {
        super("生成已停止；未完成响应不作为有效规划或正文。");
    }
}
