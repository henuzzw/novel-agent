package com.novelagent.modelaccess.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.novelagent.modelaccess.application.StructuredModelGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.agent.application.AgentRunRecorder;
import com.novelagent.memory.application.ModelContextProperties;
import com.novelagent.modelaccess.application.ChatGptDirectGateway;
import com.novelagent.modelaccess.application.ChatGptTransportService;
import com.novelagent.modelaccess.application.CodexProjectGateway;
import com.novelagent.modelaccess.application.StagePromptContributor;
import com.novelagent.modelaccess.domain.CodexSessionPolicy;
import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.ingest.infrastructure.ImportAnalysisPrompt;
import com.novelagent.ingest.infrastructure.ImportAnalysisPromptContributor;
import com.novelagent.planning.application.ScenePlanningGuide;
import com.novelagent.planning.infrastructure.OutlinePromptContributor;
import com.novelagent.planning.infrastructure.OutlineOutputSchema;
import com.novelagent.project.application.CreativeBoundaryService;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.prompt.application.AgentPromptService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

class ModelExecutorWiringTest {
    @Test
    void springInjectsFourAdaptersIntoOneGatewayWithoutExternalServices() {
        try (var context = new AnnotationConfigApplicationContext()) {
            for (Class<?> type :
                    new Class<?>[] {
                        AgentRunRecorder.class,
                        CodexAppServerClient.class,
                        CodexAgentSessionRepository.class,
                        DeepSeekStructuredOutputClient.class,
                        ChatGptDirectGateway.class,
                        ChatGptTransportService.class,
                        CodexProjectGateway.class,
                        AgentPromptService.class,
                        CreativeBoundaryService.class,
                        ProjectAccessService.class
                    }) registerMock(context, type);
            context.registerBean(ObjectMapper.class, () -> new ObjectMapper());
            context.registerBean(ModelContextProperties.class, () -> new ModelContextProperties());
            context.register(
                    LegacyCodexPromptGateway.class,
                    LegacyCodexExecutor.class,
                    ProjectCodexExecutor.class,
                    DeepSeekExecutor.class,
                    ChatGptHttpExecutor.class,
                    ModelPromptAssembler.class,
                    RoutedStructuredModelGateway.class);
            context.register(
                    com.novelagent.planning.infrastructure.OutlinePromptContributor.class,
                    com.novelagent.ingest.infrastructure.ImportAnalysisPromptContributor.class);
            context.refresh();
            assertThat(context.getBeansOfType(AbstractModelExecutor.class).values())
                    .extracting(AbstractModelExecutor::entry)
                    .containsExactlyInAnyOrder(AbstractModelExecutor.Entry.values());
            assertThat(context.getBean(StructuredModelGateway.class)).isNotNull();
            assertThat(context.getBeansOfType(StructuredModelGateway.class)).hasSize(1);
        }
    }

    @Test
    void modulePromptRulesPreserveWireTextAndOrderUnderSpringInjection() {
        try (var context = new AnnotationConfigApplicationContext()) {
            context.registerBean(
                    ModelPromptAssembler.class,
                    () ->
                            new ModelPromptAssembler(
                                    null,
                                    null,
                                    null,
                                    context.getBeanProvider(StagePromptContributor.class)
                                            .orderedStream()
                                            .toList()));
            // Register in reverse order to verify the declared stage rule ordering.
            context.register(OutlinePromptContributor.class, ImportAnalysisPromptContributor.class);
            context.refresh();
            var mapper = new ObjectMapper();
            var assembler = context.getBean(ModelPromptAssembler.class);
            var outlineSchema = new OutlineOutputSchema(mapper).value();
            for (var schema : List.of(outlineSchema, outlineSchema.at("/properties/content"))) {
                var request =
                        assembler.assemble(
                                UUID.randomUUID(),
                                "IMPORT_SOURCE_ANALYSIS",
                                ModelProvider.DEEPSEEK,
                                "stage",
                                "source",
                                schema,
                                "test",
                                1000,
                                CodexSessionPolicy.NEW_THREAD,
                                "boundary");
                String expectedStage =
                        "stage"
                                + ImportAnalysisPrompt.WORLD_ASSESSMENT_RULES
                                + "\n"
                                + ScenePlanningGuide.planningRules()
                                + new PlainTextOutputProtocol(schema).instructions();
                assertThat(request.user())
                        .isEqualTo("【本轮服务端执行规范】\n" + expectedStage + "boundary\n【本轮创作资料】\nsource");
            }
            var plain =
                    assembler.assemble(
                            UUID.randomUUID(),
                            "MANUSCRIPT",
                            ModelProvider.DEEPSEEK,
                            "stage",
                            "source",
                            mapper.nullNode(),
                            "text",
                            1000,
                            CodexSessionPolicy.NEW_THREAD,
                            "");
            assertThat(plain.user())
                    .isEqualTo(
                            "【本轮服务端执行规范】\nstage"
                                    + new PlainTextOutputProtocol(mapper.nullNode()).instructions()
                                    + "\n【本轮创作资料】\nsource");
        }
    }

    private static <T> void registerMock(
            AnnotationConfigApplicationContext context, Class<T> type) {
        context.registerBean(type, () -> mock(type));
    }
}
