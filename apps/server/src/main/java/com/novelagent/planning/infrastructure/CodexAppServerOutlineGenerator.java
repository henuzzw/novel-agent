package com.novelagent.planning.infrastructure;

import com.novelagent.modelaccess.application.StructuredModelGateway;
import com.novelagent.modelaccess.domain.ModelProvider;
import org.springframework.stereotype.Component;

@Component
public class CodexAppServerOutlineGenerator extends StructuredOutlineGenerator {
    public CodexAppServerOutlineGenerator(StructuredModelGateway models, OutlineModelPromptFactory prompts,
            OutlineModelOutputParser parser, OutlineOutputSchema schema) {
        super(ModelProvider.LOCAL_CODEX, models, prompts, parser, schema);
    }
}
