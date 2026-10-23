package com.novelagent.prompt.application;

import com.novelagent.planning.application.CharacterBlueprintGuide;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提示词唯一来源：classpath 下 {@code prompts/<key>.txt}（源码位于 src/main/resources/prompts）。
 * 修改提示词直接编辑这些 UTF-8 文件并重新构建部署；运行时数据与输出 Schema 仍由各工作流负责。
 */
public final class AgentPromptDefaults {
    private static final Map<String, String> TEMPLATES = new ConcurrentHashMap<>();
    private AgentPromptDefaults() { }

    public static String sessionSystemPrompt() { return system("CONVERSATION_SYSTEM"); }

    public static String sessionSystemPrompt(String key) {
        return switch (key) {
            case "SNOWFLAKE_PLANNING" -> system("SNOWFLAKE_SYSTEM");
            case "PROJECT_CHAT" -> system("PROJECT_CHAT_SYSTEM");
            default -> sessionSystemPrompt();
        };
    }

    public static String system(String key) {
        if (key == null || !key.matches("[A-Z_]+") || key.startsWith("CREATION_PREPARATION")) {
            throw new IllegalArgumentException("未知提示词模板：" + key);
        }
        return TEMPLATES.computeIfAbsent(key, AgentPromptDefaults::readTemplate);
    }

    private static String readTemplate(String key) {
        String path = "/prompts/" + key.toLowerCase(Locale.ROOT) + ".txt";
        try (var input = AgentPromptDefaults.class.getResourceAsStream(path)) {
            if (input == null) throw new IllegalArgumentException("未知提示词模板：" + key);
            String text = new String(input.readAllBytes(), StandardCharsets.UTF_8).strip();
            if (text.isBlank()) throw new IllegalStateException("默认提示词不能为空：" + key);
            return text + switch (key) {
                case "CHARACTER_DESIGN" -> "\n\n" + CharacterBlueprintGuide.designRules()
                        + CharacterBlueprintGuide.boundaries();
                case "OUTLINE", "PLANNING_CHECKPOINT" -> "\n\n" + CharacterBlueprintGuide.boundaries();
                default -> "";
            };
        } catch (IOException failure) {
            throw new IllegalStateException("默认提示词无法读取：" + key, failure);
        }
    }
}
