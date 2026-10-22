package com.novelagent.planning.infrastructure;

import com.novelagent.modelaccess.application.StructuredModelGateway;
import com.novelagent.modelaccess.domain.ModelProvider;
import org.springframework.stereotype.Component;

@Component
public class DeepSeekOutlineGenerator extends StructuredOutlineGenerator {
    public DeepSeekOutlineGenerator(StructuredModelGateway models, OutlineModelPromptFactory prompts,
            OutlineModelOutputParser parser, OutlineOutputSchema schema) {
        super(ModelProvider.DEEPSEEK, models, prompts, parser, schema);
    }
}
