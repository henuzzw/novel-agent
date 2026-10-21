package com.novelagent.modelaccess.infrastructure;

import com.novelagent.platform.support.Sha256;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Exact server-section references, never model-written summaries or guessed deletions. */
public final class CodexMaterialDelta {
    private static final Pattern HEADING = Pattern.compile("(?m)^【[^\\r\\n】]+】[^\\r\\n]*\\r?$");
    public static final String READ_RULES = "\n【资料引用规则】\n"
            + "以下 material_id 指向本次任务的完整资料，不是删减摘要。已发送的相同资料可沿用会话上下文；"
            + "遇到记忆缺失或需要准确细节、引文、硬约束时，必须通过 novel_read_material 分段读回，不能猜测。"
            + "标为首次提供的引用必须读取后使用。工具只读本次授权资料，不能发布、改库或执行命令。"
            + "每轮明确资料及其版本优先于旧候选；旧输出和未完成轮次不是已确认小说事实。\n";

    private CodexMaterialDelta() { }
    public static Result build(String prompt, Set<String> previouslySent) {
        String marker = "【本轮创作资料】\n";
        int dataStart = prompt.indexOf(marker);
        String prefix = dataStart < 0 ? "" : prompt.substring(0, dataStart + marker.length());
        prompt = dataStart < 0 ? prompt : prompt.substring(dataStart + marker.length());
        var sections = HEADING.matcher(prompt);
        var text = new StringBuilder(prefix);
        var materials = new LinkedHashMap<String, String>();
        int cursor = 0;
        while (sections.find()) {
            append(prompt.substring(cursor, sections.start()), previouslySent, text, materials);
            text.append(sections.group()).append('\n');
            cursor = sections.end();
            if (cursor < prompt.length() && prompt.charAt(cursor) == '\n') cursor++;
        }
        append(prompt.substring(cursor), previouslySent, text, materials);
        return new Result(text.toString(), Map.copyOf(materials));
    }
    private static void append(String block, Set<String> sent, StringBuilder text, Map<String, String> materials) {
        if (block.length() < 1000) { text.append(block); return; }
        String id = Sha256.ofUtf8(block);
        materials.put(id, block);
        if (sent.contains(id) || block.length() > 8000) {
            text.append("资料引用 material_id=").append(id).append("；字符数=").append(block.length())
                    .append(sent.contains(id) ? "；此前提供过，同一完整版本。\n" : "；首次提供，须使用只读工具读取。\n");
        } else text.append(block);
    }
    public record Result(String text, Map<String, String> materials) { }
}
