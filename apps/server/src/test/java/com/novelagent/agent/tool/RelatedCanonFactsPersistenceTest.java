package com.novelagent.agent.tool;

import static com.novelagent.support.TestDatabase.env;
import static org.assertj.core.api.Assertions.assertThat;
import com.novelagent.support.DatabaseTest;
import com.novelagent.support.TestDatabase;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

@DatabaseTest
class RelatedCanonFactsPersistenceTest {
    @Test void recallsAuthoritativeFactsWithRevisionAndChapterBoundariesWithoutNeo4j() throws Exception {
        try (var connection=DriverManager.getConnection(TestDatabase.url(),env("DB_USERNAME","novel_app"),env("DB_PASSWORD",""))) {
            var jdbc=new JdbcTemplate(new SingleConnectionDataSource(connection,true));
            jdbc.execute("CREATE TEMP TABLE canon_commit(id uuid,project_id uuid,chapter_number int,canon_version bigint,active boolean)");
            jdbc.execute("CREATE TEMP TABLE story_fact(id uuid,project_id uuid,source_commit_id uuid,subject_text text,predicate text,object_text text,evidence_ref text,canon_version_from bigint,canon_version_to bigint,subject_entity_id uuid)");
            jdbc.execute("CREATE TEMP TABLE story_entity(id uuid,project_id uuid,canonical_name text,canon_version_to bigint)");
            UUID project=UUID.randomUUID(), first=UUID.randomUUID(), second=UUID.randomUUID(), future=UUID.randomUUID(), retired=UUID.randomUUID(), other=UUID.randomUUID();
            jdbc.update("INSERT INTO canon_commit VALUES (?,?,1,1,true),(?,?,2,2,true),(?,?,3,3,true),(?,?,1,1,false),(?,?,1,1,true)",first,project,second,project,future,project,retired,project,other,UUID.randomUUID());
            insert(jdbc,project,first,"旧关系",1,5L);
            insert(jdbc,project,first,"已修正关系",5,null);
            UUID narrator=UUID.randomUUID();
            jdbc.update("INSERT INTO story_entity VALUES (?,?,'江澈',NULL)", narrator, project);
            jdbc.update("UPDATE story_fact SET subject_text='第一人称叙述者',subject_entity_id=? WHERE object_text='已修正关系'",narrator);
            insert(jdbc,project,second,"第二章后果",2,null);
            insert(jdbc,project,future,"未来事实",3,null);
            insert(jdbc,project,retired,"已撤回",1,null);
            insert(jdbc,UUID.randomUUID(),other,"其他项目",1,null);
            var tool=new RelatedCanonFactsTool(jdbc);
            assertThat(tool.optional()).isFalse();
            var current=tool.execute(new NovelToolRequest(project,3,10,"江澈",20,20)).graphFacts();
            assertThat(current).extracting(f -> f.object()).containsExactly("第二章后果","已修正关系");
            var historical=tool.execute(new NovelToolRequest(project,2,3,"江澈",20,20)).graphFacts();
            assertThat(historical).extracting(f -> f.object()).containsExactly("旧关系");
        }
    }
    private void insert(JdbcTemplate jdbc,UUID project,UUID commit,String object,long from,Long to) {
        jdbc.update("INSERT INTO story_fact VALUES (?,?,?,'江澈','关系',?,'原文',?,?,NULL)",UUID.randomUUID(),project,commit,object,from,to);
    }
}
