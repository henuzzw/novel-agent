package com.novelagent.agent.application;

import java.util.regex.Pattern;

/** Recognizes explicit input-access failures, not plot structure, style, length or literary quality. */
public final class ModelOutputAvailability {
    public static final String FAILURE_MARKER = "[NOVEL_INPUT_UNAVAILABLE]";
    public static final String INSTRUCTIONS = "\n【资料不可用时】\n如果必需资料无法读取，不用错误说明冒充创作结果，"
            + "仅返回 " + FAILURE_MARKER + " 后写明缺失资料；服务端将记录为失败，不进入下一步。\n";
    private static final Pattern LEGACY_REFUSAL = Pattern.compile(
            "^(?:当前|本轮|目前|我(?:当前)?|由于当前)[^。！？\\n]{0,220}"
            + "(?:没有(?:可用的)?(?:本地)?(?:文件|资料)读取工具|没有本地文件读取工具|无法读取(?:本轮|指定|完整|本地)[^。！？\\n]{0,80}(?:资料|来源|文件))",
            Pattern.DOTALL);
    private ModelOutputAvailability() { }

    public static boolean unavailable(String text) {
        if (text == null) return false;
        String value = text.strip();
        if (value.startsWith(FAILURE_MARKER)) return true;
        if (value.length() > 1500) return false;
        value = value.replaceFirst("^## @materials/openQuestions\\s*", "").replaceFirst("^-\\s*", "");
        return LEGACY_REFUSAL.matcher(value).find();
    }

    public static void requireAvailable(String text) {
        if (unavailable(text)) throw new ModelInputUnavailableException();
    }
}
