package org.shelterconnect.api;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
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
class InquiryConcurrencyPostgresTest {
    @Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired JsonMapper json;@Autowired JwtTestSupport tokens;@Autowired PlatformTransactionManager manager;
    UUID author,requester,postId,room;String authorToken,requesterToken;ExecutorService executor;
    @BeforeAll static void migrate()throws Exception{SchemaMigrationTest.migratePostgres();}
    @BeforeEach void setup()throws Exception{
        author=UUID.randomUUID();requester=UUID.randomUUID();UUID a=UUID.randomUUID(),b=UUID.randomUUID();executor=Executors.newFixedThreadPool(2);
        for(var pair:List.of(new UUID[]{author,a},new UUID[]{requester,b}))jdbc.update("INSERT INTO shelter.app_users(id,display_name,auth_provider,auth_subject) VALUES (?,'문의 경합 검사',?,?)",pair[0],tokens.properties.providerKey(),pair[1].toString());authorToken=tokens.token(a);requesterToken=tokens.token(b);
        postId=id(mvc.perform(post("/v1/community/posts").header("Authorization","Bearer "+authorToken).contentType("application/json").content("{\"clientRequestId\":\""+UUID.randomUUID()+"\",\"category\":\"NEIGHBOR_NEWS\",\"publication\":\"PUBLISHED\",\"title\":\"가상 소식\",\"text\":\"가상 검사\",\"regionLabel\":\"춘천시\"}")).andReturn());
    }
    @AfterEach void cleanup(){executor.close();jdbc.update("DELETE FROM shelter.inquiry_messages WHERE room_id IN (SELECT id FROM shelter.inquiry_rooms WHERE post_id=?)",postId);jdbc.update("DELETE FROM shelter.inquiry_rooms WHERE post_id=?",postId);jdbc.update("DELETE FROM shelter.community_posts WHERE id=?",postId);jdbc.update("DELETE FROM shelter.app_users WHERE id IN (?,?)",author,requester);}
    @Test void simultaneousOpenReturnsOneRoom()throws Exception{
        var start=new CountDownLatch(1);var a=executor.submit(()->{start.await();return open();});var b=executor.submit(()->{start.await();return open();});start.countDown();
        assertThat(id(a.get(15,TimeUnit.SECONDS))).isEqualTo(id(b.get(15,TimeUnit.SECONDS)));assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.inquiry_rooms WHERE post_id=?",Integer.class,postId)).isEqualTo(1);
    }
    @Test void simultaneousRetryInsertsExactlyOneMessage()throws Exception{
        room=id(open());UUID request=UUID.randomUUID();var start=new CountDownLatch(1);var a=executor.submit(()->{start.await();return send(request,requesterToken);});var b=executor.submit(()->{start.await();return send(request,requesterToken);});start.countDown();
        assertThat(id(a.get(15,TimeUnit.SECONDS))).isEqualTo(id(b.get(15,TimeUnit.SECONDS)));assertThat(jdbc.queryForObject("SELECT last_sequence FROM shelter.inquiry_rooms WHERE id=?",Long.class,room)).isEqualTo(1);
    }
    @Test void BothParticipantsSendingConcurrentlyReceiveDistinctSequences()throws Exception{
        room=id(open());var start=new CountDownLatch(1);var a=executor.submit(()->{start.await();return send(UUID.randomUUID(),authorToken);});var b=executor.submit(()->{start.await();return send(UUID.randomUUID(),requesterToken);});start.countDown();
        id(a.get(15,TimeUnit.SECONDS));id(b.get(15,TimeUnit.SECONDS));assertThat(jdbc.queryForList("SELECT sequence FROM shelter.inquiry_messages WHERE room_id=? ORDER BY sequence",Long.class,room)).containsExactly(1L,2L);
    }
    @Test void waitingSendRechecksPostVisibilityAfterLockIsReleased()throws Exception{
        room=id(open());var pending=new AtomicReference<Future<MvcResult>>();new TransactionTemplate(manager).executeWithoutResult(tx->{
            int pid=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);jdbc.update("UPDATE shelter.community_posts SET hidden_at=now() WHERE id=?",postId);pending.set(executor.submit(()->send(UUID.randomUUID(),requesterToken)));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;while(System.nanoTime()<deadline){waiting=jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM pg_locks WHERE NOT granted AND ?=ANY(pg_blocking_pids(pid)))",Boolean.class,pid);if(waiting)break;try{Thread.sleep(10);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}}assertThat(waiting).isTrue();
        });assertThat(pending.get().get(15,TimeUnit.SECONDS).getResponse().getStatus()).isEqualTo(409);assertThat(jdbc.queryForObject("SELECT last_sequence FROM shelter.inquiry_rooms WHERE id=?",Long.class,room)).isZero();
    }
    private MvcResult open()throws Exception{return mvc.perform(post("/v1/community/posts/"+postId+"/inquiries").header("Authorization","Bearer "+requesterToken)).andReturn();}
    private MvcResult send(UUID request,String token)throws Exception{return mvc.perform(post("/v1/inquiry-rooms/"+room+"/messages").header("Authorization","Bearer "+token).contentType("application/json").content("{\"clientMessageId\":\""+request+"\",\"kind\":\"TEXT\",\"text\":\"동시 메시지\"}")).andReturn();}
    private UUID id(MvcResult result)throws Exception{assertThat(result.getResponse().getStatus()).isEqualTo(200);return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).at("/data/id").asText());}
}
