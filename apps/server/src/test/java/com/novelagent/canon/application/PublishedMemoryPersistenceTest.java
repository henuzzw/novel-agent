package com.novelagent.canon.application;

import static com.novelagent.support.TestDatabase.env;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.novelagent.canon.domain.CanonCommit;
import com.novelagent.canon.infrastructure.CanonCommitRepository;
import com.novelagent.canon.infrastructure.JdbcEntityResolutionStore;
import com.novelagent.modelaccess.domain.ModelProvider;
import com.novelagent.project.application.ProjectAccessService;
import com.novelagent.project.application.ResourceVersionConflictException;
import com.novelagent.project.domain.EntryMode;
import com.novelagent.project.domain.NovelProject;
import com.novelagent.support.DatabaseTest;
import com.novelagent.support.TestDatabase;
import com.novelagent.writing.domain.*;
import com.novelagent.writing.infrastructure.ManuscriptVersionRepository;
import com.novelagent.writing.infrastructure.WritingGenerationGateway;
import jakarta.persistence.EntityManager;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Real transactions in session-local PostgreSQL tables; no application startup or novel writes. */
@DatabaseTest
class PublishedMemoryPersistenceTest {
    @Test void factIdentityAllowsHistoricalRevisionsButOnlyOneActiveProposal() throws Exception {
        try (var f = new Fixture("SUCCEEDED")) {
            f.jdbc.execute("ALTER TABLE story_fact ADD COLUMN proposal_id text NOT NULL DEFAULT 'memory_03'");
            f.jdbc.execute("ALTER TABLE story_fact ADD CONSTRAINT uq_story_fact_proposal UNIQUE(source_commit_id,proposal_id)");
            var migration=new ClassPathResource("db/migration/V064__versioned_memory_fact_identity.sql");
            try (var input=migration.getInputStream()) {
                f.jdbc.execute(new String(input.readAllBytes(),StandardCharsets.UTF_8));
            }
            f.jdbc.update("INSERT INTO story_fact(id,source_commit_id,canon_version_from) VALUES (?,?,1)",UUID.randomUUID(),f.id);
            f.jdbc.update("UPDATE story_fact SET canon_version_to=2");
            f.jdbc.update("INSERT INTO story_fact(id,source_commit_id,canon_version_from) VALUES (?,?,2)",UUID.randomUUID(),f.id);
            assertThat(f.jdbc.queryForObject("SELECT count(*) FROM story_fact",Integer.class)).isEqualTo(2);
            assertThat(f.jdbc.queryForObject("SELECT count(*) FROM story_fact WHERE canon_version_to IS NULL",Integer.class)).isEqualTo(1);
            assertThatThrownBy(() -> f.jdbc.update("INSERT INTO story_fact(id,source_commit_id,canon_version_from) VALUES (?,?,3)",UUID.randomUUID(),f.id))
                    .isInstanceOf(DuplicateKeyException.class);
        }
    }

    @Test void rematerializingAnEntityMentionUpdatesItsResolutionWithoutDuplicateRows() throws Exception {
        try (var f = new Fixture("SUCCEEDED")) {
            f.jdbc.execute("CREATE TEMP TABLE story_entity(id uuid,project_id uuid,entity_type text,canon_version_to bigint)");
            f.jdbc.execute("CREATE TEMP TABLE entity_mention(id uuid,project_id uuid,source_commit_id uuid,proposal_id text,mention_role text,mention_text text,requested_entity_id uuid,resolved_entity_id uuid,resolution_method text,confidence numeric,evidence_ref text,CONSTRAINT uq_entity_mention_source UNIQUE(source_commit_id,proposal_id,mention_role))");
            UUID first=UUID.randomUUID(), corrected=UUID.randomUUID();
            f.jdbc.update("INSERT INTO story_entity VALUES (?,?,'CHARACTER',NULL),(?,?,'CHARACTER',NULL)",first,f.project,corrected,f.project);
            var resolver=new EntityResolutionService(new JdbcEntityResolutionStore(f.jdbc));
            resolver.resolve(f.project,f.id,1,"memory_03","SUBJECT","CHARACTER","江澈",first.toString(),"旧证据");
            resolver.resolve(f.project,f.id,2,"memory_03","SUBJECT","CHARACTER","沈秋",corrected.toString(),"新证据");
            assertThat(f.jdbc.queryForObject("SELECT count(*) FROM entity_mention",Integer.class)).isEqualTo(1);
            assertThat(f.jdbc.queryForObject("SELECT resolved_entity_id FROM entity_mention",UUID.class)).isEqualTo(corrected);
            assertThat(f.jdbc.queryForObject("SELECT evidence_ref FROM entity_mention",String.class)).isEqualTo("新证据");
            UUID otherCommit=UUID.randomUUID();
            resolver.resolve(f.project,otherCommit,3,"memory_03","SUBJECT","CHARACTER","江澈",first.toString(),"其他来源");
            assertThat(f.jdbc.queryForObject("SELECT count(*) FROM entity_mention",Integer.class)).isEqualTo(2);
        }
    }

    @Test void rollbackPreventsPartialMemoryAndFailedCandidatesRemainEditable() throws Exception {
        try (var f = new Fixture("RUNNING")) {
            var a = f.fact("a", "归还纸条");
            var b = f.fact("b", "结束谈话");
            when(f.gateway.publishedMemory(any(), any(), any(), any(), anyString())).thenReturn(new ChapterReviewContent("记忆", List.of(), List.of(a,b)));
            doAnswer(call -> { f.jdbc.update("INSERT INTO story_fact VALUES (?,?,?,NULL)", UUID.randomUUID(), f.id, 1); throw new IllegalStateException("simulated write failure"); })
                    .when(f.materializer).materialize(any(), anyInt(), any(), anyLong(), anyList());
            f.service.process(new PublishedMemoryService.Source(f.id, f.project, ModelProvider.LOCAL_CODEX,
                    new ManuscriptContent("标题", f.body, "摘要", List.of()), EntityCatalogContext.empty()));
            assertThat(f.jdbc.queryForObject("SELECT count(*) FROM story_fact", Integer.class)).isZero();
            assertThat(f.state()).isEqualTo("FAILED");
            assertThat(f.jdbc.queryForObject("SELECT jsonb_array_length(candidates) FROM canon_memory_job", Integer.class)).isEqualTo(2);
            verifyNoInteractions(f.events);
            assertThat(f.commit.getAcceptedFacts()).isEmpty();
        }
    }

    @Test void editsAndRemovalsSaveHistoryRetireOldFactsAndRejectStaleVersions() throws Exception {
        try (var f = new Fixture("SUCCEEDED")) {
            var old = PublishedMemoryPolicy.decide(f.fact("a", "归还纸条"), FactDecision.ACCEPTED);
            f.commit.replaceMemoryFacts(List.of(old));
            f.jdbc.update("UPDATE canon_memory_job SET candidates=?::jsonb", f.mapper.writeValueAsString(List.of(old)));
            f.jdbc.update("INSERT INTO story_fact VALUES (?,?,?,NULL)", UUID.randomUUID(), f.id, 1);
            doAnswer(call -> { f.jdbc.update("UPDATE story_fact SET canon_version_to=? WHERE source_commit_id=? AND canon_version_to IS NULL", call.getArguments()[1], call.getArguments()[0]); return null; })
                    .when(f.materializer).retire(any(), anyLong());
            doAnswer(call -> { for (var ignored : (List<?>)call.getArgument(4)) f.jdbc.update("INSERT INTO story_fact VALUES (?,?,?,NULL)", UUID.randomUUID(), f.id, call.getArguments()[3]); return null; })
                    .when(f.materializer).materialize(any(), anyInt(), any(), anyLong(), anyList());
            var updated = PublishedMemoryPolicy.decide(f.fact("a", "结束谈话"), FactDecision.ACCEPTED);
            var view = f.service.save(f.project,1,f.id,new PublishedMemoryService.Confirmation(3,List.of(updated)));
            assertThat(view.status()).isEqualTo("SUCCEEDED");
            assertThat(view.version()).isEqualTo(4);
            assertThat(f.commit.getAcceptedFacts()).containsExactly(updated);
            assertThat(f.jdbc.queryForObject("SELECT count(*) FROM story_fact WHERE canon_version_to IS NULL", Integer.class)).isEqualTo(1);
            assertThat(f.service.history(f.project,1,f.id).getFirst().candidates()).containsExactly(old);
            assertThatThrownBy(() -> f.service.save(f.project,1,f.id,new PublishedMemoryService.Confirmation(3,List.of(old))))
                    .isInstanceOf(ResourceVersionConflictException.class);
            var removed = PublishedMemoryPolicy.decide(updated, FactDecision.REJECTED);
            f.service.save(f.project,1,f.id,new PublishedMemoryService.Confirmation(4,List.of(removed)));
            assertThat(f.commit.getAcceptedFacts()).isEmpty();
            assertThat(f.jdbc.queryForObject("SELECT count(*) FROM story_fact WHERE canon_version_to IS NULL", Integer.class)).isZero();
            assertThat(f.service.history(f.project,1,f.id)).hasSize(2);
            verifyNoInteractions(f.gateway);
        }
    }

    @Test void readinessBlocksFailedOrUnconfirmedMemoryAndOldDownstreamDependencies() throws Exception {
        try (var f = new Fixture("NEEDS_CONFIRMATION")) {
            var readiness = new PublishedMemoryReadiness(f.jdbc);
            for (String state : List.of("NEEDS_CONFIRMATION","FAILED","RUNNING","PENDING")) {
                f.jdbc.update("UPDATE canon_memory_job SET status=?", state);
                assertThatThrownBy(() -> readiness.requireReady(f.project,2)).isInstanceOf(IllegalStateException.class).hasMessageContaining("第1章");
            }
            f.jdbc.update("UPDATE canon_memory_job SET status='SUCCEEDED'");
            readiness.requireReady(f.project,2);
            UUID later = UUID.randomUUID();
            f.jdbc.update("INSERT INTO canon_commit VALUES (?,?,2,2,true,'2000-01-01')", later,f.project);
            f.jdbc.update("INSERT INTO canon_memory_job(commit_id,project_id,status,candidates,row_version) VALUES (?,?,'SUCCEEDED','[]',0)",later,f.project);
            f.jdbc.update("INSERT INTO canon_memory_history VALUES (?,?,?,3,'EDIT','[]',now())",UUID.randomUUID(),f.id,f.project);
            assertThatThrownBy(() -> readiness.requireReady(f.project,3)).isInstanceOf(IllegalStateException.class).hasMessageContaining("第2章起");
        }
    }

    static class Fixture implements AutoCloseable {
        final Connection connection;
        final UUID project=UUID.randomUUID(), id=UUID.randomUUID();
        final String body="江澈把纸条还给沈秋。她点头，结束谈话。";
        final ObjectMapper mapper=new ObjectMapper();
        final CanonCommit commit=new CanonCommit(id,project,1,UUID.randomUUID(),null,1,List.of());
        final WritingGenerationGateway gateway=mock(WritingGenerationGateway.class);
        final TypedCanonMaterializer materializer=mock(TypedCanonMaterializer.class);
        final CanonPublicationEvents events=mock(CanonPublicationEvents.class);
        final JdbcTemplate jdbc;
        final PublishedMemoryService service;
        Fixture(String state) throws Exception {
            connection=DriverManager.getConnection(TestDatabase.url(),env("DB_USERNAME","novel_app"),env("DB_PASSWORD",""));
            var ds=new SingleConnectionDataSource(connection,true); jdbc=new JdbcTemplate(ds);
            jdbc.execute("CREATE TEMP TABLE canon_commit(id uuid,project_id uuid,chapter_number int,canon_version bigint,active boolean,created_at timestamptz)");
            jdbc.execute("CREATE TEMP TABLE canon_memory_job(commit_id uuid,project_id uuid,status text,candidates jsonb,source_body text,error_message text,row_version bigint,updated_at timestamptz)");
            jdbc.execute("CREATE TEMP TABLE canon_memory_history(id uuid,commit_id uuid,project_id uuid,memory_version bigint,action text,candidates jsonb,created_at timestamptz DEFAULT now())");
            jdbc.execute("CREATE TEMP TABLE story_fact(id uuid,source_commit_id uuid,canon_version_from bigint,canon_version_to bigint)");
            jdbc.update("INSERT INTO canon_commit VALUES (?,?,1,1,true,now())",id,project);
            jdbc.update("INSERT INTO canon_memory_job VALUES (?,?,?,'[]',?,NULL,3,now())",id,project,state,body);
            var access=mock(ProjectAccessService.class); var novel=NovelProject.create(project,UUID.randomUUID(),"测试",EntryMode.MATERIALS); novel.commitCanon(0);
            when(access.requireOwnedProject(project)).thenReturn(novel);
            var commits=mock(CanonCommitRepository.class); when(commits.findById(id)).thenReturn(Optional.of(commit));
            when(commits.findByProjectIdAndChapterNumberAndActiveTrue(project,1)).thenReturn(Optional.of(commit));
            var catalog=mock(EntityCatalogService.class); when(catalog.forPublishedMemory(project)).thenReturn(EntityCatalogContext.empty());
            service=new PublishedMemoryService(jdbc,mapper,access,commits,mock(ManuscriptVersionRepository.class),mock(CharacterNameService.class),catalog,gateway,materializer,events,mock(EntityManager.class),new TransactionTemplate(new DataSourceTransactionManager(ds)));
        }
        FactProposal fact(String id,String object) { return new FactProposal(id,"EVENT_CREATE","谈话","结果",object,body,FactDecision.PENDING); }
        String state() { return jdbc.queryForObject("SELECT status FROM canon_memory_job WHERE commit_id=?",String.class,id); }
        public void close() throws Exception { connection.close(); }
    }
}
