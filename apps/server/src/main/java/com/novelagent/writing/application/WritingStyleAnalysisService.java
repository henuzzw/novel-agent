package com.novelagent.writing.application;

import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.domain.WritingStyleProfile;
import com.novelagent.writing.infrastructure.WritingGenerationGateway;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * 样本风格分析。
 *
 * <p>将文字或 UTF-8 文件转为结构化表达技法，保留样本支持的规律和证据。分析是候选，不移植样本人物剧情或自动应用项目；上传有类型与大小限制。</p>
 */
@Service
public class WritingStyleAnalysisService {
    private final WritingStyleService styles;
    private final WritingGenerationGateway gateway;

    public WritingStyleAnalysisService(WritingStyleService styles, WritingGenerationGateway gateway) {
        this.styles = styles;
        this.gateway = gateway;
    }

    public record Analysis(WritingStyleProfile profile, String analysisMode, int sampleCharacters) { }

    /**
     * 对样本文字进行结构化风格分析，输出可编辑技法候选及原文依据，不移植样本人物、事件或自动应用项目。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param sample 仅用于表达方式分析的文字样本，不移植为故事事实。
     * @param provider 明确选择的生成供应商；本地模板不代表真实文学生成。
     */
    public Analysis analyze(UUID projectId, String sample, ModelProvider provider) {
        styles.get(projectId);
        if (sample == null || sample.strip().length() < 80 || sample.length() > 12000) {
            throw new IllegalArgumentException("风格样本需要 80 至 12000 个字符");
        }
        ModelProvider selected = provider == null ? ModelProvider.LOCAL_TEMPLATE : provider;
        WritingStyleProfile profile = gateway.analyzeStyle(projectId, sample.strip(), selected);
        return new Analysis(profile, selected == ModelProvider.LOCAL_TEMPLATE ? "TEXT_METRICS" : "MODEL", sample.strip().length());
    }

    /**
     * 限定 TXT/Markdown、字节大小及 UTF-8 解码后分析风格，输出候选档案，不保存样本剧情或自动应用。
     *
     * @param projectId 小说项目 ID，用于限定业务与数据访问范围。
     * @param filename 上传文件名，用于格式识别，不当成可信磁盘路径。
     * @param bytes 上传原始字节，解码及大小限制由当前入口检查。
     * @param provider 明确选择的生成供应商；本地模板不代表真实文学生成。
     */
    public Analysis upload(UUID projectId, String filename, byte[] bytes, ModelProvider provider) {
        styles.get(projectId);
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if ((!name.endsWith(".txt") && !name.endsWith(".md")) || bytes.length > 100000) {
            throw new IllegalArgumentException("请上传不超过 100 KB 的 UTF-8 TXT 或 Markdown 文字样本");
        }
        try {
            String sample = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            if (sample.startsWith("\uFEFF")) sample = sample.substring(1);
            return analyze(projectId, sample, provider);
        } catch (CharacterCodingException exception) {
            throw new IllegalArgumentException("样本不是有效 UTF-8 文本", exception);
        }
    }
}
