package com.novelagent.canon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.infrastructure.EventFactProjector;
import com.novelagent.canon.infrastructure.StateFactProjector;
import com.novelagent.canon.infrastructure.RelationshipFactProjector;
import com.novelagent.canon.infrastructure.KnowledgeFactProjector;
import com.novelagent.canon.infrastructure.ForeshadowFactProjector;
import com.novelagent.writing.domain.FactDecision;
import com.novelagent.writing.domain.FactProposal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class TypedCanonMaterializerTest {

    @Test
    void routesAnExtensionAfterSavingTheGenericFactWithoutRequiringASubject() {
        var jdbc = mock(JdbcTemplate.class);
        var resolver = mock(EntityResolutionService.class);
        var projector = mock(CanonFactProjector.class);
        when(projector.factTypes()).thenReturn(Set.of("CUSTOM", "CUSTOM_CHANGE"));
        var sync = mock(com.novelagent.planning.application.PlanningMaterialSyncService.class);
        var materializer = new TypedCanonMaterializer(new com.novelagent.canon.infrastructure.JdbcCanonFactStore(jdbc), List.of(projector), resolver, sync);
        var fact = fact("F1", " custom_change ", "线索", "出现", "信封");
        UUID project = UUID.randomUUID();
        UUID commit = UUID.randomUUID();
        materializer.materialize(project, 3, commit, 7, List.of(fact));
        var generic = ArgumentCaptor.forClass(Object[].class);
        var projection = ArgumentCaptor.forClass(CanonFactProjector.Projection.class);
        var order = org.mockito.Mockito.inOrder(jdbc, projector, sync);
        order.verify(jdbc).update(anyString(), generic.capture());
        order.verify(projector).project(projection.capture());
        order.verify(sync).syncCanonForeshadows(project);
        assertThat(projection.getValue()).isEqualTo(new CanonFactProjector.Projection(
                project, 3, commit, 7, null, (UUID) generic.getValue()[0], fact));
        verifyNoInteractions(resolver);
    }

    @Test
    void rejectsDuplicateTypesIncludingNormalizedAliases() {
        var first = mock(CanonFactProjector.class);
        var second = mock(CanonFactProjector.class);
        when(first.factTypes()).thenReturn(Set.of("EVENT"));
        when(second.factTypes()).thenReturn(Set.of(" event "));
        assertThatThrownBy(() -> new TypedCanonMaterializer(mock(CanonFactStore.class), List.of(first, second),
                mock(EntityResolutionService.class), mock(com.novelagent.planning.application.PlanningMaterialSyncService.class)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("EVENT");
    }

    @Test
    void retiresEachStrategyOnceEvenWhenItHasMultipleAliases() {
        var jdbc = mock(JdbcTemplate.class);
        var projector = mock(CanonFactProjector.class);
        when(projector.factTypes()).thenReturn(Set.of("EVENT", "EVENT_CREATE"));
        var materializer = new TypedCanonMaterializer(new com.novelagent.canon.infrastructure.JdbcCanonFactStore(jdbc), List.of(projector), mock(EntityResolutionService.class),
                mock(com.novelagent.planning.application.PlanningMaterialSyncService.class));
        UUID commit = UUID.randomUUID();
        materializer.retire(commit, 8);
        verify(jdbc).update(anyString(), eq(8L), eq(commit));
        verify(projector).retire(commit, 8);
    }

    @Test
    void projectionFailurePropagatesWithoutSynchronizingPlanning() {
        var projector = mock(CanonFactProjector.class);
        var sync = mock(com.novelagent.planning.application.PlanningMaterialSyncService.class);
        when(projector.factTypes()).thenReturn(Set.of("EVENT_CREATE"));
        doThrow(new IllegalStateException("projection failed")).when(projector).project(any());
        var materializer = new TypedCanonMaterializer(mock(CanonFactStore.class), List.of(projector),
                mock(EntityResolutionService.class), sync);
        assertThatThrownBy(() -> materializer.materialize(UUID.randomUUID(), 1, UUID.randomUUID(), 1,
                List.of(fact("F1", "EVENT_CREATE", "事件", "发生", "结果"))))
                .isInstanceOf(IllegalStateException.class).hasMessage("projection failed");
        verify(sync, never()).syncCanonForeshadows(any());
    }

    @Test
    void storesEveryFactAndMaterializesKnownTypes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EntityResolutionService resolver = mock(EntityResolutionService.class);
        when(resolver.resolve(any(), any(), any(Long.class), anyString(), anyString(), anyString(),
                anyString(), any(), anyString()))
                .thenReturn(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        TypedCanonMaterializer materializer = new TypedCanonMaterializer(new com.novelagent.canon.infrastructure.JdbcCanonFactStore(jdbc), projectors(jdbc, resolver), resolver,
                mock(com.novelagent.planning.application.PlanningMaterialSyncService.class));

        materializer.materialize(UUID.randomUUID(), 3, UUID.randomUUID(), 7, List.of(
                fact("F1", "EVENT", "顾弦", "发现", "旧笔记"),
                fact("F2", "STATE", "顾弦", "当前位置", "旧图书馆"),
                fact("F3", "RELATION", "顾弦", "信任", "林夏"),
                fact("F4", "KNOWLEDGE", "顾弦", "得知", "门禁密码"),
                fact("F5", "FORESHADOW", "旧笔记", "暗示", "校史被篡改")));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc, times(10)).update(sql.capture(), any(Object[].class));
        String statements = String.join("\n", sql.getAllValues());
        assertThat(statements).contains("clock_timestamp()");
        assertThat(statements).contains("INSERT INTO story_fact", "INSERT INTO story_event",
                "INSERT INTO entity_state_change", "INSERT INTO story_relationship",
                "INSERT INTO character_knowledge", "INSERT INTO foreshadow");
    }

    @Test
    void keepsUnknownStateAsGenericFactWithoutGuessingAField() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EntityResolutionService resolver = mock(EntityResolutionService.class);
        when(resolver.resolve(any(), any(), any(Long.class), anyString(), anyString(), anyString(),
                anyString(), any(), anyString())).thenReturn(UUID.randomUUID());
        TypedCanonMaterializer materializer = new TypedCanonMaterializer(new com.novelagent.canon.infrastructure.JdbcCanonFactStore(jdbc), projectors(jdbc, resolver), resolver,
                mock(com.novelagent.planning.application.PlanningMaterialSyncService.class));

        materializer.materialize(UUID.randomUUID(), 1, UUID.randomUUID(), 1,
                List.of(fact("F1", "STATE", "顾弦", "章节退出状态", "沉默离场")));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("INSERT INTO story_fact");
    }

    private List<CanonFactProjector> projectors(JdbcTemplate jdbc, EntityResolutionService resolver) {
        return List.of(new EventFactProjector(jdbc), new StateFactProjector(jdbc, new ObjectMapper()),
                new RelationshipFactProjector(jdbc, resolver), new KnowledgeFactProjector(jdbc), new ForeshadowFactProjector(jdbc));
    }

    private FactProposal fact(String id, String type, String subject, String predicate, String object) {
        return new FactProposal(id, type, subject, predicate, object, "第3章正文", FactDecision.ACCEPTED);
    }
}
