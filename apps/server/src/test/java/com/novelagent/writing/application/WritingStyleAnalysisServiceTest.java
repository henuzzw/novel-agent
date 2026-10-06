package com.novelagent.writing.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.writing.infrastructure.WritingGenerationGateway;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WritingStyleAnalysisServiceTest {
    private final WritingStyleService styles = mock(WritingStyleService.class);
    private final WritingGenerationGateway gateway = mock(WritingGenerationGateway.class);
    private final WritingStyleAnalysisService service = new WritingStyleAnalysisService(styles, gateway);
    private final UUID project = UUID.randomUUID();
    @Test void extractsCandidateWithoutApplyingItAndSupportsUtf8Bom() {
        String sample = "样本中的人物与情节不能进入故事。".repeat(10);
        var profile = WritingStylePresets.all().getFirst();
        when(gateway.analyzeStyle(project, sample, ModelProvider.LOCAL_TEMPLATE)).thenReturn(profile);
        var result = service.upload(project, "sample.TXT", ("\uFEFF" + sample).getBytes(StandardCharsets.UTF_8), null);
        assertThat(result.profile()).isEqualTo(profile);
        assertThat(result.analysisMode()).isEqualTo("TEXT_METRICS");
        assertThat(result.sampleCharacters()).isEqualTo(sample.length());
        org.mockito.Mockito.verify(styles, org.mockito.Mockito.never()).apply(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anyLong());
    }
    @Test void rejectsBadEncodingExtensionAndOversizedOrShortSamplesBeforeCallingModel() {
        assertThatThrownBy(() -> service.upload(project, "sample.txt", new byte[]{(byte) 0xc3, 0x28}, null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upload(project, "sample.pdf", new byte[100], null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.upload(project, "sample.txt", new byte[100001], null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.analyze(project, "短文", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.analyze(project, "文".repeat(12001), null)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(gateway);
    }
    @Test void allPresetsAreCompleteDistinctEditableProfiles() {
        assertThat(WritingStylePresets.all()).hasSize(11).extracting(p -> p.name()).doesNotHaveDuplicates();
    }
}
