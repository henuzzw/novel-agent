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

@Service
public class WritingStyleAnalysisService {
    private final WritingStyleService styles;
    private final WritingGenerationGateway gateway;

    public WritingStyleAnalysisService(WritingStyleService styles, WritingGenerationGateway gateway) {
        this.styles = styles;
        this.gateway = gateway;
    }

    public record Analysis(WritingStyleProfile profile, String analysisMode, int sampleCharacters) { }

    public Analysis analyze(UUID projectId, String sample, ModelProvider provider) {
        styles.get(projectId);
        if (sample == null || sample.strip().length() < 80 || sample.length() > 12000) {
            throw new IllegalArgumentException("风格样本需要 80 至 12000 个字符");
        }
        ModelProvider selected = provider == null ? ModelProvider.LOCAL_TEMPLATE : provider;
        WritingStyleProfile profile = gateway.analyzeStyle(projectId, sample.strip(), selected);
        return new Analysis(profile, selected == ModelProvider.LOCAL_TEMPLATE ? "TEXT_METRICS" : "MODEL", sample.strip().length());
    }

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
