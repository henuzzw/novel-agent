package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.memory.application.MemoryBudgetAllocator;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.planning.application.ModelProvider;
import org.junit.jupiter.api.Test;

class StructuredRequestBudgetTest {
    @Test
    void includesSchemaAndReservesAtTheExactBoundaryWithoutTruncation() {
        var schema = new ObjectMapper().createObjectNode().put("description", "小说资料");
        String system = "固定角色";
        String user = "作者本次要求";
        int input = MemoryBudgetAllocator.estimateTokens(system + "\n\n" + user + "\n\n" + schema);
        var context = new ModelContextProperties();
        context.getModels().put(ModelProvider.DEEPSEEK, new ModelContextProperties.Capacity(input + 30, 10));
        var budget = new StructuredRequestBudget(context);

        assertThat(budget.requireCapacity(ModelProvider.DEEPSEEK, system, user, schema, 20).estimatedInputTokens())
                .isEqualTo(input);
        assertThatThrownBy(() -> budget.requireCapacity(ModelProvider.DEEPSEEK, system, user, schema, 21))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("上下文容量");
        assertThat(schema.path("description").asText()).isEqualTo("小说资料");
    }

    @Test
    void treatsAbsentPromptAsEmptyButRejectsNegativeReservation() {
        var schema = new ObjectMapper().createObjectNode();
        var budget = new StructuredRequestBudget(new ModelContextProperties());
        assertThat(budget.requireCapacity(ModelProvider.LOCAL_CODEX, null, null, schema, 0).estimatedInputTokens())
                .isEqualTo(MemoryBudgetAllocator.estimateTokens("\n\n\n\n" + schema));
        assertThatThrownBy(() -> budget.requireCapacity(ModelProvider.LOCAL_CODEX, "", "", schema, -1))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不能为负数");
    }
}
