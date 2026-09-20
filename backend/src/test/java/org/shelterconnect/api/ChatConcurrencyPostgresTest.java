package org.shelterconnect.api;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shelterconnect.api.auth.JwtTestConfiguration;
import org.shelterconnect.api.auth.JwtTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@Import(JwtTestConfiguration.class)
class ChatConcurrencyPostgresTest {
	@Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired JsonMapper json;
	@Autowired JwtTestSupport tokens; @Autowired PlatformTransactionManager manager;
	private UUID user,subject,shelter,dog,session;
	private String token;
	private ExecutorService executor;
	@BeforeAll static void migrate() throws Exception { SchemaMigrationTest.migratePostgres(); }
	@BeforeEach void prepare() throws Exception {
		user=UUID.randomUUID(); subject=UUID.randomUUID(); shelter=UUID.randomUUID(); dog=UUID.randomUUID(); session=null;
		executor=Executors.newFixedThreadPool(2);
		jdbc.update("INSERT INTO shelter.app_users(id,display_name,auth_provider,auth_subject) VALUES (?,'대화 동시성 테스트',?,?)",user,tokens.properties.providerKey(),subject.toString());
		jdbc.update("INSERT INTO shelter.shelters(id,name,region,is_public,approval_status,reviewed_by,reviewed_at) VALUES (?,'대화 테스트','가상',true,'APPROVED',?,now())",shelter,user);
		jdbc.update("INSERT INTO shelter.dogs(id,shelter_id,name,avatar_key,is_public,adoption_status) VALUES (?,?,'테스트','test',true,'AVAILABLE')",dog,shelter);
		token=tokens.token(subject);
		mvc.perform(get("/v1/me").header("Authorization","Bearer "+token)).andExpect(status().isOk());
	}
	@AfterEach void clean() {
		if(executor!=null) executor.close();
		jdbc.update("DELETE FROM shelter.chat_messages WHERE dog_id=?",dog);
		jdbc.update("DELETE FROM shelter.chat_sessions WHERE user_id=?",user);
		jdbc.update("DELETE FROM shelter.dogs WHERE id=?",dog);
		jdbc.update("DELETE FROM shelter.shelters WHERE id=?",shelter);
		jdbc.update("DELETE FROM shelter.app_users WHERE id=?",user);
	}
	@Test void concurrentOpeningCreatesOnlyOneReusableSession() throws Exception {
		var start=new CountDownLatch(1);
		var first=executor.submit(()->{start.await();return open();});
		var second=executor.submit(()->{start.await();return open();});
		start.countDown();
		var a=first.get(15,TimeUnit.SECONDS);var b=second.get(15,TimeUnit.SECONDS);
		assertThat(List.of(code(a),code(b))).containsExactlyInAnyOrder(201,200);
		assertThat(id(a)).isEqualTo(id(b));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.chat_sessions WHERE user_id=?",Integer.class,user)).isEqualTo(1);
	}
	@ParameterizedTest @ValueSource(booleans={false,true})
	void concurrentMessageKeysAreDeduplicatedOrConflicted(boolean different) throws Exception {
		session=id(open());
		var start=new CountDownLatch(1);
		var a=executor.submit(()->{start.await();return send("request-1","안녕");});
		var b=executor.submit(()->{start.await();return send("request-1",different?"다른 내용":"안녕");});
		start.countDown();
		var first=a.get(15,TimeUnit.SECONDS);var second=b.get(15,TimeUnit.SECONDS);
		assertThat(List.of(code(first),code(second))).containsExactlyInAnyOrder(201,different?409:200);
		if(!different) assertThat(id(first)).isEqualTo(id(second));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.chat_messages WHERE session_id=?",Integer.class,session)).isEqualTo(1);
	}
	@Test void distinctMessagesRemainOrderedAfterWaitingForTheSameSession() throws Exception {
		session=id(open());
		var start=new CountDownLatch(1);
		var a=executor.submit(()->{start.await();return send("a","첫 요청");});
		var b=executor.submit(()->{start.await();return send("b","두 번째 요청");});
		start.countDown();
		assertThat(code(a.get(15,TimeUnit.SECONDS))).isEqualTo(201);
		assertThat(code(b.get(15,TimeUnit.SECONDS))).isEqualTo(201);
		var result=mvc.perform(get("/v1/chat-sessions/"+session+"/messages").header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn();
		var data=json.readTree(result.getResponse().getContentAsString()).get("data");
		assertThat(data.size()).isEqualTo(2);
		assertThat(java.time.Instant.parse(data.get(0).get("createdAt").asText())).isBefore(java.time.Instant.parse(data.get(1).get("createdAt").asText()));
	}
	@ParameterizedTest @ValueSource(strings={"account","dog","shelter","session"})
	void stateChangesWhileSenderWaitsAreRechecked(String change) throws Exception {
		session=id(open());
		var future=new AtomicReference<Future<MvcResult>>();
		new TransactionTemplate(manager).executeWithoutResult(tx->{
			int blocker=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
			switch(change) {
				case "account" -> jdbc.update("UPDATE shelter.app_users SET disabled_at=now() WHERE id=?",user);
				case "dog" -> jdbc.update("UPDATE shelter.dogs SET adoption_status='PAUSED' WHERE id=?",dog);
				case "shelter" -> jdbc.update("UPDATE shelter.shelters SET is_public=false WHERE id=?",shelter);
				case "session" -> jdbc.update("UPDATE shelter.chat_sessions SET status='CLOSED' WHERE id=?",session);
				default -> throw new IllegalArgumentException();
			}
			future.set(executor.submit(()->send("pending","저장되면 안 됨")));
			awaitLock(blocker);
		});
		assertThat(code(future.get().get(15,TimeUnit.SECONDS))).isEqualTo(change.equals("account")?403:409);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.chat_messages WHERE session_id=?",Integer.class,session)).isZero();
	}
	@ParameterizedTest @ValueSource(strings={"account","dog","shelter"})
	void openingRechecksEligibilityAfterWaiting(String change) throws Exception {
		var future=new AtomicReference<Future<MvcResult>>();
		new TransactionTemplate(manager).executeWithoutResult(tx->{
			int blocker=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
			if(change.equals("account")) jdbc.update("UPDATE shelter.app_users SET disabled_at=now() WHERE id=?",user);
			else if(change.equals("dog")) jdbc.update("UPDATE shelter.dogs SET is_public=false WHERE id=?",dog);
			else jdbc.update("UPDATE shelter.shelters SET is_public=false WHERE id=?",shelter);
			future.set(executor.submit(this::open)); awaitLock(blocker);
		});
		assertThat(code(future.get().get(15,TimeUnit.SECONDS))).isEqualTo(change.equals("account")?403:404);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.chat_sessions WHERE user_id=?",Integer.class,user)).isZero();
	}
	private void awaitLock(int blocker) {
		long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5); boolean waiting=false;
		while(System.nanoTime()<deadline) {
			waiting=jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE NOT granted AND ?=ANY(pg_blocking_pids(pid)))",Boolean.class,blocker);
			if(waiting) break;
			try { Thread.sleep(10); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
		}
		assertThat(waiting).as("request must wait on the changed database row").isTrue();
	}
	private MvcResult open() throws Exception { return mvc.perform(post("/v1/dogs/"+dog+"/chat-sessions").header("Authorization","Bearer "+token)).andReturn(); }
	private MvcResult send(String key,String text) throws Exception {
		return mvc.perform(post("/v1/chat-sessions/"+session+"/messages").header("Authorization","Bearer "+token).contentType("application/json")
				.content(json.createObjectNode().put("clientMessageId",key).put("text",text).toString())).andReturn();
	}
	private int code(MvcResult result) { return result.getResponse().getStatus(); }
	private UUID id(MvcResult result) throws Exception { return UUID.fromString(json.readTree(result.getResponse().getContentAsString()).at("/data/id").asText()); }
}
