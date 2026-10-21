package com.novelagent.platform.support;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Agent Graph 状态的读写工具。
 *
 * <p>图状态只保存字符串，结构化对象以 JSON 文本存取；{@code label} 作为错误信息前缀区分具体工作流。</p>
 */
public final class GraphStateCodec {
    private final ObjectMapper mapper;
    private final String label;

    public GraphStateCodec(ObjectMapper mapper, String label) {
        this.mapper = mapper;
        this.label = label;
    }

    /** 读取必填字符串字段；缺失时视为工作流编排错误。 */
    public static String required(OverAllState state, String key) {
        return state.value(key, String.class)
                .orElseThrow(() -> new IllegalStateException("Agent Graph 状态缺少字段：" + key));
    }

    /** 读取可选字符串字段；缺失或空白时返回 {@code null}。 */
    public static String optional(OverAllState state, String key) {
        String value = state.value(key, "");
        return value.isBlank() ? null : value;
    }

    public String write(Object value) {
        return Json.call(() -> mapper.writeValueAsString(value), label + "状态序列化失败");
    }

    public <T> T read(String value, Class<T> type) {
        return Json.call(() -> mapper.readValue(value, type), label + "状态反序列化失败");
    }

    public <T> T read(OverAllState state, String key, Class<T> type) {
        return read(required(state, key), type);
    }
}
