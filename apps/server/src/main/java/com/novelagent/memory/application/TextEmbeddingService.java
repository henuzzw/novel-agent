package com.novelagent.memory.application;

/**
 * 文本向量接口。
 *
 * <p>抽象文本到固定维度向量的生成能力，供语义召回及投影使用。调用方必须保持索引与实现的维度、模型一致，不能混用不同向量空间。</p>
 */
public interface TextEmbeddingService {
    /**
     * 返回本实现的向量维度，必须与索引及调用方配置一致。
     */
    int dimensions();

    /**
     * 返回当前嵌入实现的模型标识，供索引来源与配置核对使用。
     */
    String modelName();

    /**
     * 将输入文本转换为本实现的向量，并遵循配置维度；外部实现失败不能返回伪造的成功结果。
     *
     * @param text 待渲染、检索或嵌入的文本，不自动成为正史事实。
     */
    float[] embed(String text);
}
