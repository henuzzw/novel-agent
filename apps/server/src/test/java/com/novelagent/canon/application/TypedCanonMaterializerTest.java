package com.novelagent.canon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.writing.domain.FactDecision;
import com.novelagent.writing.domain.FactProposal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

class TypedCanonMaterializerTest {

    @Test
    void storesEveryFactAndMaterializesKnownTypes() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        EntityResolutionService resolver = mock(EntityResolutionService.class);
        when(resolver.resolve(any(), any(), any(Long.class), anyString(), anyString(), anyString(),
                anyString(), any(), anyString()))
                .thenReturn(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                        UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        TypedCanonMaterializer materializer = new TypedCanonMaterializer(jdbc, new ObjectMapper(), resolver,
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
        TypedCanonMaterializer materializer = new TypedCanonMaterializer(jdbc, new ObjectMapper(), resolver,
                mock(com.novelagent.planning.application.PlanningMaterialSyncService.class));

        materializer.materialize(UUID.randomUUID(), 1, UUID.randomUUID(), 1,
                List.of(fact("F1", "STATE", "顾弦", "章节退出状态", "沉默离场")));

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        verify(jdbc).update(sql.capture(), any(Object[].class));
        assertThat(sql.getValue()).contains("INSERT INTO story_fact");
    }

    private FactProposal fact(String id, String type, String subject, String predicate, String object) {
        return new FactProposal(id, type, subject, predicate, object, "第3章正文", FactDecision.ACCEPTED);
    }
}
