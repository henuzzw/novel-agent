package com.novelagent.prompt.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.prompt.domain.PromptConfiguration;
import com.novelagent.prompt.domain.PromptRevision;
import com.novelagent.prompt.infrastructure.AgentPromptRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgentPromptServiceTest {
    private final UUID user = UUID.randomUUID();
    private final AgentPromptCatalog catalog = new AgentPromptCatalog();
    private final AgentPromptRepository repository = mock(AgentPromptRepository.class);
    private final AgentPromptService service = new AgentPromptService(catalog, repository, new CurrentActorProvider(user));

    @Test void catalogCoversAll22WorkflowsAnd24RealDefaultTemplates() {
        assertThat(catalog.all()).hasSize(24);
        assertThat(catalog.all().stream().map(AgentPromptCatalog.Definition::workflow).distinct()).hasSize(22);
        assertThat(catalog.all().stream().map(AgentPromptCatalog.Definition::key)).doesNotHaveDuplicates();
        catalog.all().forEach(value -> assertThat(value.defaultSystemPrompt()).isNotBlank());
        assertThat(catalog.require("OUTLINE").defaultSystemPrompt()).contains("作者本轮明确要求优先");
        assertThatThrownBy(() -> catalog.require("NOT_AN_AGENT")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test void readingDefaultsDoesNotCreateDatabaseRecordsOrChangeOriginalPrompts() {
        assertThat(service.list()).allMatch(value -> value.version() == 0 && !value.customized());
        String original = "真实系统文本含动态姓名与人物边界";
        assertThat(service.resolve("MANUSCRIPT", original)).isEqualTo(new AgentPromptService.Resolved(original, null));
        verify(repository).findAll(user);
        verify(repository).find(user, "MANUSCRIPT");
        verifyNoMoreInteractions(repository);
    }

    @Test void listReadsSettingsOnceAndRetainsActorIsolation() {
        when(repository.findAll(user)).thenReturn(List.of(config("MANUSCRIPT", "作者定制", "执行规则", 3)));
        var values = service.list();
        var body = values.stream().filter(value -> value.key().equals("MANUSCRIPT")).findFirst().orElseThrow();
        assertThat(body.systemPrompt()).isEqualTo("作者定制");
        assertThat(body.guidance()).isEqualTo("执行规则");
        assertThat(body.version()).isEqualTo(3);
        assertThat(body.customized()).isTrue();
        assertThat(body.defaultSystemPrompt()).isEqualTo(AgentPromptDefaults.system("MANUSCRIPT"));
        assertThat(values.stream().filter(value -> value.key().equals("OUTLINE")).findFirst().orElseThrow().version()).isZero();
        verify(repository, times(1)).findAll(user);
        verifyNoMoreInteractions(repository);
    }

    @Test void editsOverrideTheRoleAndAddGuidanceButRetainProtectedRulesAndRevision() {
        when(repository.find(user, "MANUSCRIPT")).thenReturn(Optional.of(config("MANUSCRIPT", "新写作角色", "从具体矛盾切入", 2)));
        var result = service.resolve("MANUSCRIPT", "旧角色");
        assertThat(result.systemPrompt()).startsWith("新写作角色").contains("从具体矛盾切入", "作者本轮明确要求优先", "不提交正史", "MANUSCRIPT · 版本 2");
        assertThat(result.systemPrompt()).doesNotContain("旧角色");
        assertThat(result.revision()).isEqualTo("MANUSCRIPT:v2");
    }

    @Test void guidanceOnlyRetainsTheActualRenderedSystemPrompt() {
        when(repository.find(user, "QUALITY_REVIEW")).thenReturn(Optional.of(config("QUALITY_REVIEW", null, "核对指代", 1)));
        assertThat(service.resolve("QUALITY_REVIEW", "已渲染的原始角色与边界").systemPrompt())
                .startsWith("已渲染的原始角色与边界").contains("核对指代");
    }

    @Test void importModesUseIndependentSettingsAndUnknownVariantsAreNotGuessed() {
        when(repository.find(user, "IMPORT_REVERSE_BIBLE_CONTINUE"))
                .thenReturn(Optional.of(config("IMPORT_REVERSE_BIBLE_CONTINUE", "续写专用", "", 4)));
        String adapt = AgentPromptDefaults.system("IMPORT_REVERSE_BIBLE_ADAPT");
        assertThat(service.resolve("IMPORT_REVERSE_BIBLE", adapt).systemPrompt()).isEqualTo(adapt);
        assertThat(service.resolve("IMPORT_REVERSE_BIBLE", AgentPromptDefaults.system("IMPORT_REVERSE_BIBLE_CONTINUE")).systemPrompt()).startsWith("续写专用");
        assertThat(service.resolve("IMPORT_REVERSE_BIBLE", "无法确定模式")).isEqualTo(new AgentPromptService.Resolved("无法确定模式", null));
        verify(repository, never()).find(user, "IMPORT_REVERSE_BIBLE");
    }

    @Test void restoredDefaultsStillHaveANewRevisionForSessionRotation() {
        when(repository.find(user, "OUTLINE")).thenReturn(Optional.of(config("OUTLINE", null, "", 5)));
        var result = service.resolve("OUTLINE", "原有动态系统文本");
        assertThat(result.systemPrompt()).startsWith("原有动态系统文本").contains("版本 5 · 默认");
        assertThat(result.systemPrompt()).doesNotContain("全局阶段执行规则");
        assertThat(result.revision()).isEqualTo("OUTLINE:v5");
    }

    @Test void saveAndResetUseExpectedVersionsAndReturnPersistedValues() {
        when(repository.save(user, "OUTLINE", "新指令", "规则", 0, "SAVE")).thenReturn(true);
        when(repository.find(user, "OUTLINE")).thenReturn(Optional.of(config("OUTLINE", "新指令", "规则", 1)));
        assertThat(service.save("OUTLINE", "新指令", "规则", 0).version()).isEqualTo(1);
        when(repository.save(user, "OUTLINE", null, "", 1, "RESET")).thenReturn(true);
        when(repository.find(user, "OUTLINE")).thenReturn(Optional.of(config("OUTLINE", null, "", 2)));
        assertThat(service.reset("OUTLINE", 1).customized()).isFalse();
        verify(repository).save(user, "OUTLINE", null, "", 1, "RESET");
    }

    @Test void matchingDefaultTextIsNotStoredAsACopyOfTheBaseline() {
        String baseline = AgentPromptDefaults.system("OUTLINE");
        when(repository.save(user, "OUTLINE", null, "", 0, "SAVE")).thenReturn(true);
        service.save("OUTLINE", baseline, "", 0);
        verify(repository).save(user, "OUTLINE", null, "", 0, "SAVE");
    }

    @Test void staleUpdatesThrowInsteadOfOverwriting() {
        when(repository.find(user, "OUTLINE")).thenReturn(Optional.of(config("OUTLINE", "已更新", "", 8)));
        assertThatThrownBy(() -> service.save("OUTLINE", "旧页面编辑", "", 3))
                .isInstanceOf(ResourceVersionConflictException.class);
    }

    @Test void invalidPayloadsNeverReachPersistence() {
        assertThatThrownBy(() -> service.save("UNKNOWN", "text", "", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save("OUTLINE", " \n", "", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save("OUTLINE", "x".repeat(40001), "", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save("OUTLINE", "valid", "x".repeat(40001), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.save("OUTLINE", "valid", null, 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.reset("OUTLINE", -1)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repository);
    }

    @Test void historyIsScopedAndImmutable() {
        var revision = new PromptRevision(1, "曾用指令", "", "SAVE", Instant.now());
        when(repository.history(user, "MANUSCRIPT")).thenReturn(List.of(revision));
        assertThat(service.history("MANUSCRIPT")).containsExactly(revision);
        verify(repository).history(user, "MANUSCRIPT");
    }

    private PromptConfiguration config(String key, String system, String guidance, long version) {
        return new PromptConfiguration(key, system, guidance, version, Instant.now());
    }
}
