package com.novelagent.planning.infrastructure;

import com.novelagent.planning.application.ModelProvider;
import org.springframework.stereotype.Component;

@Component
public class CodexAppServerOutlineGenerator extends StructuredOutlineGenerator {
    public CodexAppServerOutlineGenerator(StructuredModelGateway models, OutlineModelPromptFactory prompts,
            OutlineModelOutputParser parser, OutlineOutputSchema schema) {
        super(ModelProvider.LOCAL_CODEX, models, prompts, parser, schema);
    }
}
