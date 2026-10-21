package com.novelagent.writing.domain;

import static org.assertj.core.api.Assertions.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class DraftCheckTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private DraftCheck.Issue issue(List<DraftCheck.Evidence> quotes) {
        return new DraftCheck.Issue("B-LOGIC-001", QualityDimension.LOGIC, "检查意见", quotes,
                "正文依据", "", "", "", "澄清");
    }

    @Test void savedSingleQuoteReportsAreReadableAndReSerializeAsAQuoteList() throws Exception {
        var report = new DraftCheck("检查", List.of(new DraftCheck.Issue("B-LOGIC-001", QualityDimension.LOGIC,
                "检查意见", "她收回本子。", "依据", "", "", "", "澄清")));
        var json = mapper.valueToTree(report);
        ((ObjectNode) json.path("issues").get(0)).put("evidence", "她收回本子。");
        var restored = mapper.treeToValue(json, DraftCheck.class);
        restored.requireEvidenceIn("她收回本子。");
        assertThat(restored.issues().getFirst().evidence()).containsExactly(new DraftCheck.Evidence("她收回本子。"));
        assertThat(mapper.readValue(mapper.writeValueAsString(restored), DraftCheck.class)).isEqualTo(restored);
    }

    @Test void evidenceListMustBeNonemptyBoundedAndContainOnlyNonblankQuotes() {
        assertThatThrownBy(() -> issue(List.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue(List.of(new DraftCheck.Evidence(" ")))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> issue(Collections.nCopies(DraftCheck.MAX_EVIDENCE_QUOTES + 1, new DraftCheck.Evidence("原文"))))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test void independentQuotesAreNotFuzzyMatchedOrConcatenatedAcrossParagraphs() {
        var check = new DraftCheck("检查", List.of(issue(List.of(new DraftCheck.Evidence("她收回本子。"), new DraftCheck.Evidence("我点头。")))));
        check.requireEvidenceIn("她收回本子。\n\n她拿起课本。\n\n我点头。");
        assertThatThrownBy(() -> check.requireEvidenceIn("她收回本子。\n\n我点头！")).hasMessageContaining("evidence[1]");
    }
}
