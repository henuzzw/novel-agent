package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.modelaccess.application.ChatGptDirectGateway;
import com.novelagent.modelaccess.application.ChatGptTransportService;
import com.novelagent.modelaccess.application.CodexProjectGateway;
import com.novelagent.prompt.application.AgentPromptService;
import com.novelagent.project.application.CreativeBoundaryService;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ModelExecutorWiringTest {
    @Test void springInjectsFourAdaptersIntoOneGatewayWithoutExternalServices() {
        try (var context = new AnnotationConfigApplicationContext()) {
            for (Class<?> type : new Class<?>[]{AgentRunRecorder.class, CodexAppServerClient.class,
                    CodexAgentSessionRepository.class, DeepSeekStructuredOutputClient.class,
                    ChatGptDirectGateway.class, ChatGptTransportService.class, CodexProjectGateway.class,
                    AgentPromptService.class, CreativeBoundaryService.class,
                    com.novelagent.project.application.ProjectAccessService.class}) registerMock(context, type);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(ModelContextProperties.class, () -> new ModelContextProperties());
            context.register(LegacyCodexPromptGateway.class, LegacyCodexExecutor.class, ProjectCodexExecutor.class,
                    DeepSeekExecutor.class, ChatGptHttpExecutor.class, ModelPromptAssembler.class, StructuredModelGateway.class);
            context.refresh();
            assertThat(context.getBeansOfType(AbstractModelExecutor.class).values())
                    .extracting(AbstractModelExecutor::entry).containsExactlyInAnyOrder(AbstractModelExecutor.Entry.values());
            assertThat(context.getBean(StructuredModelGateway.class)).isNotNull();
        }
    }

    private static <T> void registerMock(AnnotationConfigApplicationContext context, Class<T> type) {
        context.registerBean(type, () -> mock(type));
    }
}
