package org.shelterconnect.api;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shelterconnect.api.auth.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Import(JwtTestConfiguration.class)
class CommunityConcurrencyPostgresTest {
    @Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired JsonMapper json;@Autowired JwtTestSupport tokens;@Autowired PlatformTransactionManager manager;
    UUID user,subject,post;String token;ExecutorService executor;
    @BeforeAll static void migrate()throws Exception{SchemaMigrationTest.migratePostgres();}
    @BeforeEach void setup(){user=UUID.randomUUID();subject=UUID.randomUUID();executor=Executors.newFixedThreadPool(2);jdbc.update("INSERT INTO shelter.app_users(id,display_name,auth_provider,auth_subject) VALUES (?,'동시성 검사',?,?)",user,tokens.properties.providerKey(),subject.toString());token=tokens.token(subject);}
    @AfterEach void cleanup(){executor.close();jdbc.update("DELETE FROM shelter.community_comments WHERE author_id=?",user);jdbc.update("DELETE FROM shelter.community_posts WHERE author_id=?",user);jdbc.update("DELETE FROM shelter.app_users WHERE id=?",user);}
    @ParameterizedTest @ValueSource(booleans={false,true}) void repeatedCreateRequestsSerialize(boolean different)throws Exception{
        UUID request=UUID.randomUUID();var start=new CountDownLatch(1);
        var a=executor.submit(()->{start.await();return create(request,"소식");});var b=executor.submit(()->{start.await();return create(request,different?"다름":"소식");});start.countDown();
        var first=a.get(15,TimeUnit.SECONDS);var second=b.get(15,TimeUnit.SECONDS);assertThat(List.of(code(first),code(second))).containsExactlyInAnyOrder(200,different?409:200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.community_posts WHERE author_id=?",Integer.class,user)).isEqualTo(1);
    }
    @Test void competingVersionsAllowOnlyOneEdit()throws Exception{
        post=id(create(UUID.randomUUID(),"소식"));var start=new CountDownLatch(1);
        var a=executor.submit(()->{start.await();return edit("첫 수정");});var b=executor.submit(()->{start.await();return edit("둘째 수정");});start.countDown();
        assertThat(List.of(code(a.get(15,TimeUnit.SECONDS)),code(b.get(15,TimeUnit.SECONDS)))).containsExactlyInAnyOrder(200,409);
        assertThat(jdbc.queryForObject("SELECT version FROM shelter.community_posts WHERE id=?",Long.class,post)).isEqualTo(2);
    }
    @Test void sightingWaitingOnPostLockRechecksClosedState()throws Exception{
        post=id(create(UUID.randomUUID(),"소식"));var pending=new AtomicReference<Future<MvcResult>>();
        new TransactionTemplate(manager).executeWithoutResult(tx->{
            int pid=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);jdbc.update("UPDATE shelter.community_posts SET status='CLOSED' WHERE id=?",post);
            pending.set(executor.submit(()->mvc.perform(post("/v1/community/posts/"+post+"/comments").header("Authorization","Bearer "+token).contentType("application/json").content("{\"clientRequestId\":\""+UUID.randomUUID()+"\",\"kind\":\"SIGHTING\",\"text\":\"새 제보\",\"location\":{\"label\":\"가상 위치\",\"occurredAt\":\"2026-09-30T00:00:00Z\"}}")).andReturn()));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
            while(System.nanoTime()<deadline){waiting=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE NOT granted AND ?=ANY(pg_blocking_pids(pid)))",Boolean.class,pid);if(waiting)break;try{Thread.sleep(10);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}
            assertThat(waiting).isTrue();
        });assertThat(code(pending.get().get(15,TimeUnit.SECONDS))).isEqualTo(409);assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.community_comments WHERE post_id=?",Integer.class,post)).isZero();
    }
    private MvcResult create(UUID request,String title)throws Exception{return mvc.perform(post("/v1/community/posts").header("Authorization","Bearer "+token).contentType("application/json").content("{\"clientRequestId\":\""+request+"\",\"category\":\"LOST\",\"publication\":\"PUBLISHED\",\"title\":\""+title+"\",\"text\":\"내용\",\"regionLabel\":\"가상\",\"location\":{\"label\":\"가상\",\"occurredAt\":\"2026-09-30T00:00:00Z\"}}")).andReturn();}
    private MvcResult edit(String title)throws Exception{return mvc.perform(patch("/v1/community/posts/"+post).header("Authorization","Bearer "+token).contentType("application/json").content("{\"version\":1,\"category\":\"LOST\",\"title\":\""+title+"\",\"text\":\"내용\",\"regionLabel\":\"가상\",\"location\":{\"label\":\"가상\",\"occurredAt\":\"2026-09-30T00:00:00Z\"}}")).andReturn();}
    private int code(MvcResult result){return result.getResponse().getStatus();}
    private UUID id(MvcResult result)throws Exception{assertThat(code(result)).isEqualTo(200);return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).at("/data/id").asText());}
}
