package com.novelagent.canon.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class EntityResolutionServiceTest {

    @Test
    void resolvesConfirmedAliasWithoutCreatingAnotherEntity() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID entityId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(), List.of(entityId));
        EntityResolutionService service = new EntityResolutionService(jdbc);

        UUID resolved = service.resolve(UUID.randomUUID(), UUID.randomUUID(), 3, "F1",
                "SUBJECT", "CHARACTER", "小弦", null, "正文证据");

        assertThat(resolved).isEqualTo(entityId);
        verify(jdbc, never()).queryForObject(anyString(), eq(UUID.class), any(Object[].class));
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void rejectsUnresolvedPronounInsteadOfCreatingAnEntity() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        EntityResolutionService service = new EntityResolutionService(jdbc);

        assertThatThrownBy(() -> service.resolve(UUID.randomUUID(), UUID.randomUUID(), 3, "F1",
                "SUBJECT", "CHARACTER", "她", null, "正文证据"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("无法确定代词");

        verify(jdbc, never()).queryForObject(anyString(), eq(UUID.class), any(Object[].class));
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void validatesRequestedStableIdWithinProjectAndType() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID entityId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class)))
                .thenReturn(List.of(entityId));
        EntityResolutionService service = new EntityResolutionService(jdbc);

        UUID resolved = service.resolve(UUID.randomUUID(), UUID.randomUUID(), 3, "F1",
                "SUBJECT", "CHARACTER", "顾弦", entityId.toString(), "正文证据");

        assertThat(resolved).isEqualTo(entityId);
        verify(jdbc).update(anyString(), any(Object[].class));
    }

    @Test
    void createsNewExplicitlyNamedEntityAndRecordsTheMention() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        UUID entityId = UUID.randomUUID();
        when(jdbc.query(anyString(), any(RowMapper.class), any(Object[].class))).thenReturn(List.of());
        when(jdbc.queryForObject(anyString(), eq(UUID.class), any(Object[].class))).thenReturn(entityId);
        EntityResolutionService service = new EntityResolutionService(jdbc);

        UUID resolved = service.resolve(UUID.randomUUID(), UUID.randomUUID(), 3, "F1",
                "SUBJECT", "CHARACTER", "顾弦", null, "正文证据");

        assertThat(resolved).isEqualTo(entityId);
        verify(jdbc).update(anyString(), any(Object[].class));
    }
}
