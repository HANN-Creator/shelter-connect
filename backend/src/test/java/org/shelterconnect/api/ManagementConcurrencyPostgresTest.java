package org.shelterconnect.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Committed, unique fixtures let independent connections exercise real lock races. */
@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@Import(JwtTestConfiguration.class)
class ManagementConcurrencyPostgresTest {
	@Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired JsonMapper json;
	@Autowired JwtTestSupport tokens; @Autowired PlatformTransactionManager manager;
	private UUID user,subject,shelter,otherShelter,dog;
	private String token,version;
	private ExecutorService executor;
	@BeforeAll static void migrate() throws Exception { SchemaMigrationTest.migratePostgres(); }
	@BeforeEach void prepare() throws Exception {
		user=UUID.randomUUID(); subject=UUID.randomUUID(); shelter=UUID.randomUUID(); otherShelter=UUID.randomUUID(); dog=UUID.randomUUID();
		executor=Executors.newFixedThreadPool(2);
		jdbc.update("INSERT INTO shelter.app_users(id,display_name,auth_provider,auth_subject) VALUES (?,'동시성 테스트',?,?)",user,tokens.properties.providerKey(),subject.toString());
		for(UUID id:List.of(shelter,otherShelter)) jdbc.update("INSERT INTO shelter.shelters(id,name,region,approval_status,reviewed_by,reviewed_at) VALUES (?,'동시성 테스트','가상','APPROVED',?,now())",id,user);
		jdbc.update("INSERT INTO shelter.shelter_memberships(user_id,shelter_id,status) VALUES (?,?,'ACTIVE')",user,shelter);
		jdbc.update("INSERT INTO shelter.dogs(id,shelter_id,name,avatar_key) VALUES (?,?,'원래 이름','test')",dog,shelter);
		token=tokens.token(subject);
		var initial=mvc.perform(get(path()).header("Authorization","Bearer "+token)).andExpect(status().isOk()).andReturn();
		version=json.readTree(initial.getResponse().getContentAsString()).at("/data/updatedAt").asText();
	}
	@AfterEach void clean() {
		if(executor!=null) executor.close();
		jdbc.update("DELETE FROM shelter.dogs WHERE id=?",dog);
		jdbc.update("DELETE FROM shelter.shelter_memberships WHERE user_id=?",user);
		jdbc.update("DELETE FROM shelter.shelters WHERE id IN (?,?)",shelter,otherShelter);
		jdbc.update("DELETE FROM shelter.app_users WHERE id=?",user);
	}
	@Test void simultaneousPatchesCannotSilentlyOverwriteEachOther() throws Exception {
		var start=new CountDownLatch(1);
		Future<Integer> first=executor.submit(()->{ start.await(); return update("첫 수정"); });
		Future<Integer> second=executor.submit(()->{ start.await(); return update("두 번째 수정"); });
		start.countDown();
		assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
		assertThat(jdbc.queryForObject("SELECT name FROM shelter.dogs WHERE id=?",String.class,dog)).isIn("첫 수정","두 번째 수정");
	}
	@ParameterizedTest @ValueSource(strings={"membership","account","shelter","ownership"})
	void permissionOrOwnershipChangedWhileWriterWaitsIsRechecked(String changed) throws Exception {
		var future=new java.util.concurrent.atomic.AtomicReference<Future<Integer>>();
		new TransactionTemplate(manager).executeWithoutResult(tx->{
			int blocker=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
			switch(changed) {
				case "membership" -> jdbc.update("UPDATE shelter.shelter_memberships SET status='REVOKED' WHERE user_id=?",user);
				case "account" -> jdbc.update("UPDATE shelter.app_users SET disabled_at=now() WHERE id=?",user);
				case "shelter" -> jdbc.update("UPDATE shelter.shelters SET approval_status='SUSPENDED' WHERE id=?",shelter);
				case "ownership" -> jdbc.update("UPDATE shelter.dogs SET shelter_id=? WHERE id=?",otherShelter,dog);
				default -> throw new IllegalArgumentException();
			}
			future.set(executor.submit(()->update("허용되면 안 됨")));
			// Observe a real PostgreSQL lock wait rather than relying on a scheduling delay.
			long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);
			boolean waiting=false;
			while(System.nanoTime()<deadline) {
				// pg_locks is live; pg_stat_activity can retain a transaction's earlier process snapshot.
				waiting=jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE NOT granted AND ? = ANY(pg_blocking_pids(pid)))",Boolean.class,blocker);
				if(waiting) break;
				try { Thread.sleep(10); } catch(InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
			}
			assertThat(waiting).as("writer must wait for %s change",changed).isTrue();
		});
		assertThat(future.get().get(15,TimeUnit.SECONDS)).isEqualTo(403);
		assertThat(jdbc.queryForObject("SELECT name FROM shelter.dogs WHERE id=?",String.class,dog)).isEqualTo("원래 이름");
	}
	private int update(String name) throws Exception {
		String body=json.createObjectNode().put("expectedUpdatedAt",version).put("name",name).toString();
		return mvc.perform(patch(path()).header("Authorization","Bearer "+token).contentType("application/json").content(body))
				.andReturn().getResponse().getStatus();
	}
	private String path() { return "/v1/shelter-admin/dogs/"+dog; }
}
