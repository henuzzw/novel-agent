package com.novelagent.agent.application;

/** A provider completed a turn without the required input; this is not a literary quality verdict. */
public class ModelInputUnavailableException extends IllegalArgumentException {
    public ModelInputUnavailableException() {
        super("模型未能读取本轮创作资料，返回的是读取失败说明；未保存为有效结果，请检查资料工具后重试。");
    }
}
