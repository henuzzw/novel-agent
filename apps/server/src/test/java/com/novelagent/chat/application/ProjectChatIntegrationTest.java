package com.novelagent.chat.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.chat.domain.ChatAction;
import com.novelagent.planning.application.ModelProvider;
import com.novelagent.planning.application.SnowflakeStep;
import com.novelagent.planning.domain.*;
import com.novelagent.planning.infrastructure.*;
import com.novelagent.project.application.CurrentActorProvider;
import com.novelagent.project.domain.*;
import com.novelagent.project.infrastructure.NovelProjectRepository;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** Real isolated migrations, ownership and atomic revisions; external model requests are substituted. */
@SpringBootTest(properties={"spring.kafka.listener.auto-startup=false","spring.kafka.admin.auto-create=false",
        "logging.file.name=target/chat-integration-test.log"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="NOVEL_CHAT_DB_TEST",matches="true")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ProjectChatIntegrationTest {
    private static final String SCHEMA="chat_test_"+UUID.randomUUID().toString().replace("-","");
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired NovelProjectRepository projects;
    @Autowired CurrentActorProvider actor;
    @Autowired ChatMaterials materials;
    @Autowired ProjectChatStore store;
    @Autowired SnowflakePlanStore snowflakes;
    @Autowired OutlineVersionRepository outlines;
    @MockitoBean StructuredModelGateway models;
    @MockitoBean com.novelagent.canon.infrastructure.OutboxPublisher outbox;
    @MockitoBean com.novelagent.memory.infrastructure.SemanticEmbeddingBackfill embeddings;

    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",()->"jdbc:postgresql://"+env("DB_HOST","localhost")+":"+env("DB_PORT","5432")
                +"/"+env("DB_NAME","novel_agent")+"?currentSchema="+SCHEMA+",public&sslmode="+env("DB_SSL_MODE","disable"));
        p.add("spring.flyway.default-schema",()->SCHEMA); p.add("spring.flyway.schemas",()->SCHEMA);
        p.add("spring.jpa.properties.hibernate.default_schema",()->SCHEMA);
    }
    private UUID project() {
        UUID id=UUID.randomUUID(); projects.saveAndFlush(NovelProject.create(id,actor.currentUserId(),"对话测试",EntryMode.MATERIALS)); return id;
    }
    private UUID candidate(UUID project, String key, String after) {
        UUID turn=UUID.randomUUID(); store.create(project,turn,"修改指定内容");
        store.complete(project,turn,"候选待确认",List.of(),List.of(new ProjectChatStore.Proposal(materials.require(project,key),after,"理由","影响")));
        return store.changes(project).getFirst().id();
    }
    @Test void modelLoopStagesProfileGroupAuthorAppliesUndoesAndRejectsStaleSources() throws Exception {
        UUID p=project(), person=UUID.randomUUID();
        jdbc.update("INSERT INTO story_entity(id,project_id,entity_type,canonical_name,canon_version_from) VALUES (?,?,'CHARACTER','林舟',0)",person,p);
        jdbc.update("INSERT INTO character_profile(character_id,project_id,background) VALUES (?,?,'旧背景')",person,p);
        String background="PROFILE:"+person+":background", desire="PROFILE:"+person+":core_desire";
        var actions=new ArrayDeque<>(List.of(new ChatAction("READ",background,0,"","","","",""),
                new ChatAction("PROPOSE",background,0,"","新背景","经历支撑选择","动机待核对",""),
                new ChatAction("READ",desire,0,"","","","",""),
                new ChatAction("PROPOSE",desire,0,"","主动争取信任","欲望明确","后续行为待核对",""),
                new ChatAction("REPLY","",0,"","","","","已提出两项修改，等待你确认。")));
        when(models.request(eq(p),eq("PROJECT_CHAT"),any(),anyString(),anyString(),any(),anyString(),anyInt(),any(),any()))
                .thenAnswer(call->{Consumer<String> callback=call.getArgument(9); callback.accept(json.writeValueAsString(actions.removeFirst()));return "";});
        UUID turn=UUID.randomUUID();
        mvc.perform(post("/api/v1/projects/"+p+"/chat/turns").contentType("application/json")
                .content(json.createObjectNode().put("id",turn.toString()).put("question","调整背景与欲望").toString())).andExpect(status().isAccepted());
        long end=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while(store.active(p,turn)&&System.nanoTime()<end) Thread.sleep(30);
        assertThat(store.get(p,turn).status()).isEqualTo("SUCCEEDED");
        assertThat(store.changes(p)).hasSize(2).allMatch(c->c.status().equals("PENDING"));
        assertThat(jdbc.queryForObject("SELECT background FROM character_profile WHERE character_id=?",String.class,person)).isEqualTo("旧背景");
        UUID change=store.changes(p).getFirst().id();
        mvc.perform(post("/api/v1/projects/"+p+"/chat/changes/"+change+"/apply")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT core_desire FROM character_profile WHERE character_id=?",String.class,person)).isEqualTo("主动争取信任");
        assertThat(jdbc.queryForObject("SELECT row_version FROM character_profile WHERE character_id=?",Long.class,person)).isEqualTo(1);
        mvc.perform(post("/api/v1/projects/"+p+"/chat/changes/"+change+"/undo")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT background FROM character_profile WHERE character_id=?",String.class,person)).isEqualTo("旧背景");
        assertThat(jdbc.queryForObject("SELECT core_desire FROM character_profile WHERE character_id=?",String.class,person)).isNull();
        UUID stale=candidate(p,background,"不能覆盖");
        jdbc.update("UPDATE character_profile SET background='作者另行修改',row_version=row_version+1 WHERE character_id=?",person);
        mvc.perform(post("/api/v1/projects/"+p+"/chat/changes/"+stale+"/apply")).andExpect(status().isBadRequest());
        assertThat(store.changes(p).getFirst().status()).isEqualTo("PENDING");
        UUID hidden=UUID.randomUUID();projects.saveAndFlush(NovelProject.create(hidden,UUID.randomUUID(),"其他作者",EntryMode.MATERIALS));
        mvc.perform(get("/api/v1/projects/"+hidden+"/chat/turns")).andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/projects/"+p+"/chat/changes/"+stale+"/reject")).andExpect(status().isNoContent());
    }
    @Test void outlineGroupCreatesOneDraftPreservesPublishedPointerAndUndoCopiesOldContent() {
        UUID p=project(), bible=UUID.randomUUID();
        jdbc.update("INSERT INTO story_bible_version(id,project_id,generation_number,schema_version,status,generator_type,content,created_at,updated_at) VALUES (?,?,1,'bible/1','PUBLISHED','TEST','{}',now(),now())",bible,p);
        var content=new OutlineContent("小说","主线","结构","节奏",20000,30000,List.of(new OutlineArc(1,"卷","目标","冲突","转折","结果",20000,30000,
                List.of(new ChapterPlan(1,"一","旧一",ChapterPlanStatus.PLANNED),new ChapterPlan(2,"二","旧二",ChapterPlanStatus.PLANNED),new ChapterPlan(3,"三","旧三",ChapterPlanStatus.PLANNED)))));
        var budget=new OutlineWordBudget(25000,20000,30000,1,3,8000,6000,10000);
        UUID base=UUID.randomUUID();var original=OutlineVersion.create(base,p,1,"TEST",null,bible,budget,content);original.publish();outlines.saveAndFlush(original);
        jdbc.update("UPDATE novel_project SET current_outline_version_id=? WHERE id=?",base,p);
        UUID turn=UUID.randomUUID();store.create(p,turn,"改前两章");
        store.complete(p,turn,"候选",List.of(),List.of(
                new ProjectChatStore.Proposal(materials.require(p,"OUTLINE:"+base+":2"),"新二","理由","影响"),
                new ProjectChatStore.Proposal(materials.require(p,"OUTLINE:"+base+":1"),"新一","理由","影响")));
        UUID change=store.changes(p).getFirst().id();store.decide(p,change,"apply");
        var draft=outlines.findFirstByProjectIdOrderByGenerationNumberDesc(p).orElseThrow();
        assertThat(draft.getGenerationNumber()).isEqualTo(2);assertThat(draft.getStatus()).isEqualTo(OutlineStatus.DRAFT);
        var chapters=draft.getContent().arcs().getFirst().chapters();
        assertThat(chapters.get(0).content()).isEqualTo("新一");assertThat(chapters.get(1).content()).isEqualTo("新二");
        assertThat(chapters.get(0).contentNeedsUpdate()).isFalse();assertThat(chapters.get(1).contentNeedsUpdate()).isFalse();
        assertThat(chapters.get(2).contentNeedsUpdate()).isTrue();
        assertThat(jdbc.queryForObject("SELECT current_outline_version_id FROM novel_project WHERE id=?",UUID.class,p)).isEqualTo(base);
        store.decide(p,change,"undo");assertThat(outlines.findFirstByProjectIdOrderByGenerationNumberDesc(p).orElseThrow().getContent()).isEqualTo(content);
        assertThat(outlines.findAllByProjectIdOrderByGenerationNumberDesc(p)).hasSize(3);
    }
    @Test void snowflakeRevisionForksValidPrefixClearsProjectionAndUndoRestoresASeparateVersion() {
        UUID p=project();UUID base=snowflakes.create(p,ModelProvider.DEEPSEEK,json.createObjectNode().put("mode","ADAPT_SOURCE"));
        for(var step:SnowflakeStep.values()) {snowflakes.start(p,base,step.name());snowflakes.save(p,base,step.name(),"原"+step.label());}
        jdbc.update("UPDATE snowflake_planning_run SET prepared_materials='{}' WHERE id=?",base);
        snowflakes.finish(p,base,"SUCCEEDED",null);
        UUID change=candidate(p,"SNOWFLAKE:"+base+":SYNOPSIS","新的故事梗概");
        store.decide(p,change,"apply");var next=snowflakes.latest(p).orElseThrow();
        assertThat(next.status()).isEqualTo("CANCELLED");assertThat(next.steps()).containsOnlyKeys("CORE","SYNOPSIS");
        assertThat(next.steps().get("SYNOPSIS")).isEqualTo("新的故事梗概");
        assertThat(jdbc.queryForObject("SELECT prepared_materials::text FROM snowflake_planning_run WHERE id=?",String.class,next.id())).isNull();
        assertThat(snowflakes.get(p,base).steps()).hasSize(9);
        store.decide(p,change,"undo");var restored=snowflakes.latest(p).orElseThrow();
        assertThat(restored.status()).isEqualTo("SUCCEEDED");assertThat(restored.steps()).isEqualTo(snowflakes.get(p,base).steps());
        assertThat(restored.id()).isNotEqualTo(base);
    }
    @AfterAll void cleanup() {jdbc.execute("DROP SCHEMA IF EXISTS "+SCHEMA+" CASCADE");}
    private static String env(String key,String fallback) {return System.getenv().getOrDefault(key,fallback);}
}
