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
class AdoptionNoteConcurrencyPostgresTest {
	@Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired JsonMapper json;
	@Autowired JwtTestSupport tokens; @Autowired PlatformTransactionManager manager;
	private UUID user, shelter, dog;
	private String token;
	private ExecutorService executor;
	@BeforeAll static void migrate() throws Exception { SchemaMigrationTest.migratePostgres(); }
	@BeforeEach void prepare() throws Exception {
		user = UUID.randomUUID(); shelter = UUID.randomUUID(); dog = UUID.randomUUID(); UUID subject = UUID.randomUUID();
		executor = Executors.newFixedThreadPool(2);
		jdbc.update("INSERT INTO shelter.app_users(id,display_name,auth_provider,auth_subject) VALUES (?,'메모 테스트',?,?)", user, tokens.properties.providerKey(), subject.toString());
		jdbc.update("INSERT INTO shelter.shelters(id,name,region,is_public,approval_status,reviewed_by,reviewed_at) VALUES (?,'메모 테스트','가상',true,'APPROVED',?,now())", shelter, user);
		jdbc.update("INSERT INTO shelter.dogs(id,shelter_id,name,avatar_key,is_public,adoption_status) VALUES (?,?,'테스트','test',true,'AVAILABLE')", dog, shelter);
		token = tokens.token(subject);
		mvc.perform(get("/v1/me").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
	}
	@AfterEach void clean() {
		if (executor != null) executor.close();
		jdbc.update("DELETE FROM shelter.adoption_notes WHERE user_id=?", user);
		jdbc.update("DELETE FROM shelter.dogs WHERE id=?", dog);
		jdbc.update("DELETE FROM shelter.shelters WHERE id=?", shelter);
		jdbc.update("DELETE FROM shelter.app_users WHERE id=?", user);
	}
	@Test void concurrentFirstSavesCreateOneNoteAndRejectTheOther() throws Exception {
		var start = new CountDownLatch(1);
		var first = executor.submit(() -> { start.await(); return save("첫 내용", null); });
		var second = executor.submit(() -> { start.await(); return save("다른 내용", null); });
		start.countDown();
		assertThat(List.of(code(first.get(15, TimeUnit.SECONDS)), code(second.get(15, TimeUnit.SECONDS)))).containsExactlyInAnyOrder(201, 409);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.adoption_notes WHERE user_id=?", Integer.class, user)).isEqualTo(1);
	}
	@Test void concurrentEditsDoNotLoseTheWinningUpdate() throws Exception {
		var original = save("기존", null);
		String version = json.readTree(original.getResponse().getContentAsString()).at("/data/updatedAt").asString();
		var start = new CountDownLatch(1);
		var first = executor.submit(() -> { start.await(); return save("첫 수정", version); });
		var second = executor.submit(() -> { start.await(); return save("다른 수정", version); });
		start.countDown();
		var a = first.get(15, TimeUnit.SECONDS); var b = second.get(15, TimeUnit.SECONDS);
		assertThat(List.of(code(a), code(b))).containsExactlyInAnyOrder(200, 409);
		var winner = code(a) == 200 ? a : b;
		assertThat(jdbc.queryForObject("SELECT questions FROM shelter.adoption_notes WHERE user_id=?", String.class, user))
				.isEqualTo(json.readTree(winner.getResponse().getContentAsString()).at("/data/questions").asString());
	}
	@ParameterizedTest @ValueSource(strings = {"account", "dog", "shelter"})
	void firstSaveRechecksEligibilityAfterWaiting(String change) throws Exception {
		var future = new AtomicReference<Future<MvcResult>>();
		new TransactionTemplate(manager).executeWithoutResult(tx -> {
			int blocker = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
			if (change.equals("account")) jdbc.update("UPDATE shelter.app_users SET disabled_at=now() WHERE id=?", user);
			else if (change.equals("dog")) jdbc.update("UPDATE shelter.dogs SET is_public=false WHERE id=?", dog);
			else jdbc.update("UPDATE shelter.shelters SET is_public=false WHERE id=?", shelter);
			future.set(executor.submit(() -> save("저장되면 안 됨", null))); awaitLock(blocker);
		});
		assertThat(code(future.get().get(15, TimeUnit.SECONDS))).isEqualTo(change.equals("account") ? 403 : 404);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.adoption_notes WHERE user_id=?", Integer.class, user)).isZero();
	}
	@ParameterizedTest @ValueSource(strings = {"account", "note"})
	void editingRechecksAccountAndVersionAfterWaiting(String change) throws Exception {
		String version = json.readTree(save("기존", null).getResponse().getContentAsString()).at("/data/updatedAt").asString();
		var future = new AtomicReference<Future<MvcResult>>();
		new TransactionTemplate(manager).executeWithoutResult(tx -> {
			int blocker = jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class);
			if (change.equals("account")) jdbc.update("UPDATE shelter.app_users SET disabled_at=now() WHERE id=?", user);
			else jdbc.update("UPDATE shelter.adoption_notes SET questions='먼저 저장한 수정' WHERE user_id=?", user);
			future.set(executor.submit(() -> save("덮어쓰면 안 됨", version))); awaitLock(blocker);
		});
		assertThat(code(future.get().get(15, TimeUnit.SECONDS))).isEqualTo(change.equals("account") ? 403 : 409);
		assertThat(jdbc.queryForObject("SELECT questions FROM shelter.adoption_notes WHERE user_id=?", String.class, user)).isEqualTo(change.equals("account") ? "기존" : "먼저 저장한 수정");
	}
	private void awaitLock(int blocker) {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5); boolean waiting = false;
		while (System.nanoTime() < deadline) {
			waiting = jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE NOT granted AND ?=ANY(pg_blocking_pids(pid)))", Boolean.class, blocker);
			if (waiting) break;
			try { Thread.sleep(10); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
		}
		assertThat(waiting).as("request must wait on the changed database row").isTrue();
	}
	private MvcResult save(String questions, String expected) throws Exception {
		var body = json.createObjectNode().put("questions", questions).put("carePlan", "").set("checklist", json.createObjectNode());
		if (expected == null) body.putNull("expectedUpdatedAt"); else body.put("expectedUpdatedAt", expected);
		return mvc.perform(put("/v1/me/adoption-notes/" + dog).header("Authorization", "Bearer " + token)
				.contentType("application/json").content(body.toString())).andReturn();
	}
	private int code(MvcResult result) { return result.getResponse().getStatus(); }
}
