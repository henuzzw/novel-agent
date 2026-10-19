package com.novelagent.canon.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.data.neo4j.core.Neo4jClient;

class Neo4jSchemaInitializerTest {
    @Test
    void createsEveryIndexIdempotently() {
        Neo4jClient neo4j = mock(Neo4jClient.class, RETURNS_DEEP_STUBS);

        assertThat(new Neo4jSchemaInitializer(neo4j).initialize()).isEqualTo(Neo4jSchemaInitializer.STATEMENTS.size());
        assertThat(Neo4jSchemaInitializer.STATEMENTS).allMatch(statement -> statement.contains("IF NOT EXISTS"))
                .noneMatch(statement -> statement.contains("CONSTRAINT"));
        verify(neo4j, times(Neo4jSchemaInitializer.STATEMENTS.size())).query(anyString());
    }

    @Test
    void unreachableNeo4jStopsAfterFirstFailureWithoutThrowing() {
        Neo4jClient neo4j = mock(Neo4jClient.class);
        when(neo4j.query(anyString())).thenThrow(new IllegalStateException("connection refused"));

        assertThat(new Neo4jSchemaInitializer(neo4j).initialize()).isZero();
        verify(neo4j, times(1)).query(anyString());
    }
}
