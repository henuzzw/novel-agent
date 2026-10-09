package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProviderException;
import com.novelagent.writing.domain.ManuscriptContent;
import java.util.List;
import org.junit.jupiter.api.Test;

class WritingQualityTest {
    @Test void sharedIssueSchemaPreservesReviewAndQualityPermissions() {
        var schemas = new WritingOutputSchemas(new ObjectMapper());
        var review = schemas.review().path("properties").path("issues").path("items");
        var quality = schemas.qualityReview().path("properties").path("issues").path("items");
        assertThat(review.path("additionalProperties").booleanValue()).isFalse();
        assertThat(quality.path("additionalProperties").booleanValue()).isFalse();
        assertThat(review.path("required")).isEqualTo(quality.path("required"));
        assertThat(review.path("required")).hasSize(7);
        for (String field : List.of("id", "description", "evidence", "suggestion", "resolved")) {
            assertThat(review.path("properties").path(field))
                    .isEqualTo(quality.path("properties").path(field));
        }
        assertThat(review.path("properties").path("severity").path("enum").toString())
                .isEqualTo("[\"BLOCKING\",\"WARNING\",\"INFO\"]");
        assertThat(quality.path("properties").path("severity").path("enum").toString())
                .isEqualTo("[\"WARNING\",\"INFO\"]");
        assertThat(review.path("properties").path("category").has("enum")).isFalse();
        assertThat(quality.path("properties").path("category").path("enum").toString())
                .isEqualTo("[\"STYLE\",\"FLUENCY\",\"LOGIC\",\"SCENE\"]");
    }

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
}
