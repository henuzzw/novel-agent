package com.novelagent.writing.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.planning.application.ModelProviderException;
import com.novelagent.writing.domain.FactDecision;
import org.junit.jupiter.api.Test;

class WritingModelOutputParserTest {
    private final WritingModelOutputParser parser = new WritingModelOutputParser(new ObjectMapper());

    @Test
    void resetsModelReviewDecisionsAndResolutionFlags() {
        String output = """
                {
                  "summary": "检查完成",
                  "issues": [{
                    "id": "I1", "severity": "WARNING", "category": "连续性",
                    "description": "描述", "evidence": "证据", "suggestion": "建议", "resolved": true
                  }],
                  "factProposals": [{
                    "id": "F1", "factType": "EVENT_CREATE", "subject": "顾弦",
                    "predicate": "发现", "object": "旧笔记", "evidence": "正文证据",
                    "confidence": 0.92,
                    "payload": {
                      "eventTitle": "发现旧笔记", "eventSummary": "顾弦发现一本旧笔记",
                      "storyTime": "开学第二天", "participants": ["顾弦"]
                    },
                    "decision": "ACCEPTED"
                  }]
                }
                """;

        var review = parser.review(output);

        assertThat(review.issues().getFirst().resolved()).isFalse();
        assertThat(review.factProposals().getFirst().decision()).isEqualTo(FactDecision.PENDING);
        assertThat(review.factProposals().getFirst().payload().storyTime()).isEqualTo("开学第二天");
    }

    @Test
    void rejectsMalformedModelOutput() {
        assertThatThrownBy(() -> parser.manuscript("不是 JSON"))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("写作结构约束");
    }

    @Test
    void rejectsFactWhosePayloadDoesNotMatchItsType() {
        String output = """
                {
                  "summary": "检查完成", "issues": [],
                  "factProposals": [{
                    "id": "F1", "factType": "STATE_CHANGE", "subject": "顾弦",
                    "predicate": "位置", "object": "图书馆", "evidence": "她走进图书馆",
                    "confidence": 0.9,
                    "payload": {"eventTitle": "错误类型"},
                    "decision": "PENDING"
                  }]
                }
                """;

        assertThatThrownBy(() -> parser.review(output))
                .isInstanceOf(ModelProviderException.class)
                .hasMessageContaining("类型化候选事实无效");
    }

    @Test
    void acceptsStateNameAndValueFromTopLevelFactFields() {
        String output = """
                {
                  "summary": "检查完成", "issues": [],
                  "factProposals": [{
                    "id": "F1", "factType": "STATE_CHANGE", "subject": "顾弦",
                    "predicate": "位置", "object": "图书馆", "evidence": "她走进图书馆",
                    "confidence": 0.9,
                    "payload": {
                      "stateEntityType": "CHARACTER", "fieldKey": "character.location",
                      "storyTime": "放学后"
                    },
                    "decision": "PENDING"
                  }]
                }
                """;

        var review = parser.review(output);

        assertThat(review.factProposals()).hasSize(1);
        assertThat(review.factProposals().getFirst().subject()).isEqualTo("顾弦");
    }

    @Test
    void acceptsEventAndStateWithoutInventingStoryTime() {
        String output = """
                {
                  "summary": "时间无法从正文确定", "issues": [],
                  "factProposals": [
                    {
                      "id": "F1", "factType": "EVENT_CREATE", "subject": "许言川",
                      "predicate": "翻找", "object": "旧相册", "evidence": "他翻出旧相册",
                      "confidence": 0.9,
                      "payload": {"eventTitle": "翻找旧相册", "eventSummary": "许言川翻找旧相册",
                        "storyTime": null, "participants": null},
                      "decision": "PENDING"
                    },
                    {
                      "id": "F2", "factType": "STATE_CHANGE", "subject": "许言川",
                      "predicate": "当前目标", "object": "找到纸条照片", "evidence": "他继续寻找照片",
                      "confidence": 0.8,
                      "payload": {"stateEntityType": "CHARACTER", "fieldKey": "character.current_goal",
                        "storyTime": null},
                      "decision": "PENDING"
                    }
                  ]
                }
                """;

        var review = parser.review(output);

        assertThat(review.factProposals()).hasSize(2);
        assertThat(review.factProposals()).allSatisfy(fact ->
                assertThat(fact.payload().storyTime()).isNull());
    }
}
