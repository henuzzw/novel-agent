package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LegacyCodexPromptReducerTest {
    private final LegacyCodexPromptReducer reducer = new LegacyCodexPromptReducer(new ObjectMapper());
    private final String prefix = "【本轮服务端执行规范】\ncurrent instructions\n【本轮创作资料】\n";

    @Test void onlyIdenticalSavedJsonBecomesAReference() {
        String data = "{\"bible\":\"" + "设定与事实".repeat(300) + "\"}";
        String prompt = prefix + "故事圣经：\n" + data + "\n作者要求：\nkeep the selected ending";
        var first = reducer.reduce("MANUSCRIPT", prompt, Set.of());
        assertThat(first.text()).isEqualTo(prompt);
        assertThat(first.materials().values()).containsExactly("\n" + data);
        var second = reducer.reduce("MANUSCRIPT", prompt, first.materials().keySet());
        assertThat(second.text()).startsWith(prefix).contains("material_id=", "作者要求：\nkeep the selected ending")
                .doesNotContain(data);
        assertThat(second.materials()).isEqualTo(first.materials());
        String changed = prompt.replace("设定与事实", "已更新事实");
        assertThat(reducer.reduce("MANUSCRIPT", changed, first.materials().keySet()).text()).isEqualTo(changed);

        String source = "{\"text\":\"" + "source ".repeat(200) + "\"}";
        var imported = reducer.reduce("IMPORT_SOURCE_ANALYSIS", prefix + source + "\ntrailing instruction", Set.of());
        assertThat(imported.materials().values()).containsExactly(source);
        var next = reducer.reduce("IMPORT_SOURCE_ANALYSIS", prefix + source + "\ntrailing instruction", imported.materials().keySet());
        assertThat(next.text()).startsWith(prefix).endsWith("\ntrailing instruction");
    }

    @Test void priorSnowflakeBlocksAreReferencedWithoutRemovingStageOrAuthorInstructions() {
        String prior = "已完成梗概".repeat(300);
        String prompt = prefix + "【本阶段】\ncurrent stage\n【CORE】\n" + prior
                + "\n【作者要求】\n" + "important instruction ".repeat(100);
        var first = reducer.reduce("SNOWFLAKE_PLANNING", prompt, Set.of());
        var next = reducer.reduce("SNOWFLAKE_PLANNING", prompt, first.materials().keySet());
        assertThat(next.text()).contains("【本阶段】\ncurrent stage", "material_id=", "important instruction ".repeat(100))
                .doesNotContain(prior);
        assertThat(first.materials().values()).containsExactly("\n" + prior + "\n");
    }

    @Test void uncertainBoundariesAndOversizedSourcesStayExactText() {
        String prompt = prefix + "【未知标题】\n" + "data ".repeat(2000)
                + "\n【保存的九步底稿】\n" + "notes ".repeat(2000) + "\nfinal instruction";
        assertThat(reducer.reduce("OUTLINE", prompt, Set.of()).text()).isEqualTo(prompt);
        assertThat(reducer.reduce("OUTLINE", prompt, Set.of()).materials()).isEmpty();

        String source = " \n原文\n【作者要求】\n" + "长资料".repeat(3000) + "\n ";
        var result = reducer.reduce("BOOK_TITLE", prefix + "故事内容\n" + source, Set.of());
        assertThat(result.text()).startsWith(prefix + "故事内容\n").contains("首次以文件提供", ".novel-materials/")
                .doesNotContain(source);
        assertThat(result.materials().values()).containsExactly(source);
    }

}
