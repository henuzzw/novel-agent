package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProviderException;
import com.novelagent.writing.domain.ManuscriptContent;
import java.util.List;
import org.junit.jupiter.api.Test;

class WritingQualityTest {
    @Test void localChecksAreEvidenceBoundAndDoNotPretendToJudgeLogic() {
        String paragraph = "然后他走到门口，接着看见纸条，随后停下来。。";
        var manuscript = new ManuscriptContent("门口", paragraph + "\n" + paragraph, "摘要", List.of());
        var report = new LocalQualityReviewer().review(manuscript);
        report.requireEvidenceIn(manuscript.body());
        assertThat(report.issues()).extracting(i -> i.category()).contains("STYLE", "FLUENCY", "SCENE");
        assertThat(report.scores()).allMatch(s -> s.score() == null);
        assertThat(report.issues()).allMatch(i -> !i.resolved() && i.severity().equals("INFO"));
    }
    @Test void rejectsModelEvidenceThatWasNotInTheManuscript() throws Exception {
        var mapper = new ObjectMapper();
        var report = new LocalQualityReviewer().review(new ManuscriptContent("标题", "他停下来。。", "摘要", List.of()));
        var parser = new WritingModelOutputParser(mapper);
        String json = mapper.writeValueAsString(report);
        assertThatThrownBy(() -> parser.qualityReview(json, new ManuscriptContent("标题", "他停下来。", "摘要", List.of())))
                .isInstanceOf(ModelProviderException.class);
        assertThat(parser.qualityReview(json, new ManuscriptContent("标题", "他停下来。。", "摘要", List.of())).issues()).hasSize(1);
    }
    @Test void metricsAndAnalysisPromptKeepSampleSeparateFromStoryFacts() {
        var profile = new LocalStyleAnalyzer().analyze("他走到门口。她停下来。".repeat(20));
        assertThat(profile.narrativeVoice()).contains("不能推断");
        assertThat(profile.avoidPatterns()).contains("移植样本人物、情节或设定");
        var prompt = new WritingPromptFactory(new ObjectMapper(), null, null, null, null).styleAnalysis("样本文字");
        assertThat(prompt.user()).contains("样本文字");
        assertThat(prompt.system()).contains("样本");
    }
}
