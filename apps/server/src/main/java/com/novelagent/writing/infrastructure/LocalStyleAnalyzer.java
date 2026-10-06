package com.novelagent.writing.infrastructure;

import com.novelagent.writing.domain.WritingStyleProfile;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
class LocalStyleAnalyzer {
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？.!?]+|\\R+");

    WritingStyleProfile analyze(String sample) {
        long sentenceCount = SENTENCE_END.splitAsStream(sample).filter(value -> !value.isBlank()).count();
        long length = sample.codePoints().filter(value -> !Character.isWhitespace(value)).count();
        long average = length / Math.max(1, sentenceCount);
        long dialogueMarks = sample.codePoints().filter(value -> value == '“' || value == '"').count();
        return new WritingStyleProfile("样本节奏参考（本地指标）", "本地指标不能推断叙述声线；沿用故事圣经与人物视角",
                "样本平均句长约 " + average + " 字；参考此节奏并保留自然的长短变化",
                "本地不能判断描写取舍；优先保留与人物感知和场景相关的具体细节",
                "样本包含约 " + dialogueMarks + " 个引号标记；对话仍需符合人物身份，不机械追求相同比例",
                "本地不能推断情绪表达方式；依据人物、动作和情境表达",
                "参考句长指标，场景节奏仍服从章节目标与因果", List.of("照搬样本原句", "移植样本人物、情节或设定"));
    }
}
