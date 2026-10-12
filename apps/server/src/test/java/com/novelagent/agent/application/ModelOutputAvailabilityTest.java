package com.novelagent.agent.application;

import static org.assertj.core.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ModelOutputAvailabilityTest {
    @Test void reportedAccessFailuresAreNotCreativeOutputs() {
        for (String response : new String[] {
                "当前环境没有可用的本地文件读取工具，无法读取指定完整来源；请将文件内容贴入对话。",
                "当前仍没有本地文件读取工具，指定的23890字完整来源尚未读取。",
                "## @materials/openQuestions\n- 本轮新版本的完整资料尚未读取，当前没有可用的本地文件读取工具。",
                "[NOVEL_INPUT_UNAVAILABLE] 指定资料无法读取"
        }) {
            assertThatThrownBy(() -> ModelOutputAvailability.requireAvailable(response))
                    .isInstanceOf(ModelInputUnavailableException.class);
        }
        assertThat(ModelFailureDetails.from(new ModelInputUnavailableException()).category()).isEqualTo("INPUT_UNAVAILABLE");
    }

    @Test void shortStoriesAndQuotedFailuresAreNotRejectedAsLiteraryDefects() {
        for (String prose : new String[] {"故事只有一句也可以。", "他看到屏幕上的字：当前没有本地文件读取工具。",
                "当前教室没有可用的电脑，他决定去图书馆。", "资料不足：老师年龄未知，人物仍按原有称谓设计。"}) {
            assertThat(ModelOutputAvailability.unavailable(prose)).isFalse();
        }
    }
}
