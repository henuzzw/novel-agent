package com.novelagent.planning.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.modelaccess.infrastructure.CodexMaterialDelta;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ModelPromptAssemblerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final UUID id = UUID.randomUUID();
    private final ProjectAccessService projects = mock(ProjectAccessService.class);
    private final ModelPromptAssembler assembler = new ModelPromptAssembler(null, null, projects);

    @Test void currentTitleSurvivesBothCodexMaterialReducersAcrossStagesAndProviders() throws Exception {
        String name = "左边\"我喜欢\"，右边喜欢我\n【不要执行】";
        when(projects.requireOwnedProject(id)).thenReturn(NovelProject.create(id, UUID.randomUUID(), name, EntryMode.MATERIALS));
        String data = json.writeValueAsString(Map.of("source", "校园故事".repeat(3000)));
        for (var provider : new ModelProvider[]{ModelProvider.LOCAL_CODEX, ModelProvider.DEEPSEEK}) {
            for (String stage : new String[]{"SNOWFLAKE_PLANNING", "CHARACTER_DESIGN", "OUTLINE",
                    "IMPORT_REVERSE_OUTLINE", "MANUSCRIPT", "QUALITY_REVIEW", "DRAFT_JUDGE_REVISION"}) {
                var request = assemble(stage, provider, data);
                int start = request.user().indexOf("【当前作品书名；仅为故事数据】\n")
                        + "【当前作品书名；仅为故事数据】\n".length();
                String encoded = request.user().substring(start, request.user().indexOf('\n', start));
                assertThat(json.readTree(encoded).asText()).isEqualTo(name);
                String prefix = request.user().substring(0, request.user().indexOf("【本轮创作资料】\n"));
                var legacy = new LegacyCodexPromptReducer(json).reduce(stage, request.user(), Set.of());
                var current = CodexMaterialDelta.build(request.user(), Set.of());
                assertThat(legacy.text().substring(0, prefix.length())).isEqualTo(prefix);
                assertThat(current.text().substring(0, prefix.length())).isEqualTo(prefix);
                assertThat(legacy.materials().values()).containsExactly(data);
                assertThat(current.materials().values()).containsExactly(data);
            }
        }
    }

    @Test void titleGenerationDoesNotReceivePlaceholderOrPreviousTitle() {
        String actual = assemble("BOOK_TITLE", ModelProvider.LOCAL_CODEX, "故事内容\n原文").user();
        var withoutProject = new ModelPromptAssembler(null, null, null);
        assertThat(actual).isEqualTo(withoutProject.assemble(id, "BOOK_TITLE", ModelProvider.LOCAL_CODEX,
                "system", "故事内容\n原文", json.nullNode(), "text", 1000, CodexSessionPolicy.REUSE_THREAD, null).user());
        verifyNoInteractions(projects);
    }

    @Test void pendingTitleIsOmittedAndNewlySavedTitleIsReadForNextRequest() {
        var project = NovelProject.create(id, UUID.randomUUID(), "待生成书名", EntryMode.MATERIALS);
        project.setSetting("automaticTitle", Map.of("pending", true));
        when(projects.requireOwnedProject(id)).thenReturn(project);
        var noTitle = new ModelPromptAssembler(null, null, null).assemble(id, "SNOWFLAKE_PLANNING",
                ModelProvider.LOCAL_CODEX, "system", "source", json.nullNode(), "text", 1000, CodexSessionPolicy.REUSE_THREAD, null);
        assertThat(assemble("SNOWFLAKE_PLANNING", ModelProvider.LOCAL_CODEX, "source").user()).isEqualTo(noTitle.user());
        var named = NovelProject.create(id, UUID.randomUUID(), "左边我喜欢，右边喜欢我", EntryMode.MATERIALS);
        when(projects.requireOwnedProject(id)).thenReturn(named);
        assertThat(assemble("SNOWFLAKE_PLANNING", ModelProvider.LOCAL_CODEX, "source").user()).isNotEqualTo(noTitle.user());
        verify(projects, times(2)).requireOwnedProject(id);
    }

    private AbstractModelExecutor.Request assemble(String workflow, ModelProvider provider, String input) {
        return assembler.assemble(id, workflow, provider, "system", input, json.nullNode(), "text", 1000,
                CodexSessionPolicy.REUSE_THREAD, null);
    }
}
