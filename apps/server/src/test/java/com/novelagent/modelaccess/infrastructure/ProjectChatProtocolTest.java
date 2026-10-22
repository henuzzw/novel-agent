package com.novelagent.modelaccess.infrastructure;

import static org.assertj.core.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.chat.application.ProjectChatService;
import com.novelagent.chat.domain.ChatAction;
import org.junit.jupiter.api.Test;

class ProjectChatProtocolTest {
    @Test void plainTextToolEnvelopePreservesNaturalReplacementAndHasNoWriteAction() throws Exception {
        var json = new ObjectMapper();
        var schema = ProjectChatService.schema(json);
        var protocol = new PlainTextOutputProtocol(schema);
        String raw = """
                ## @action
                PROPOSE
                ## @source
                OUTLINE:record:1
                ## @query
                ## @replacement
                她把纸条退回来，说：“先把这一句说清楚。”
                他没有再笑，第一次给出了选择。
                ## @reason
                以行动回应关系压力。
                ## @impact
                后续选择需要核对。
                ## @reply
                ## @offset
                0
                """;
        var action = json.readValue(protocol.decode(raw), ChatAction.class);
        assertThat(action.replacement()).contains("她把纸条退回来", "第一次给出了选择");
        assertThat(action.reply()).isEmpty();
        assertThatThrownBy(() -> protocol.decode(raw.replace("PROPOSE", "APPLY"))).hasMessageContaining("可选值无效");
    }
}
