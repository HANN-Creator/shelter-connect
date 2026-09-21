package org.shelterconnect.api;

import java.nio.file.Path;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shelterconnect.api.auth.JwtTestConfiguration;
import org.shelterconnect.api.auth.JwtTestSupport;
import org.shelterconnect.sample.SampleDataLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@Import(JwtTestConfiguration.class) @Transactional
class AdoptionNotePostgresTest {
	private static final UUID USER = id("020", 4), OTHER = id("020", 1), STAFF = id("020", 2),
			DOG = id("022", 1), DOG2 = id("022", 2), PRIVATE = id("022", 5), SHELTER = id("021", 1);
	private static final UUID SUBJECT = UUID.randomUUID(), OTHER_SUB = UUID.randomUUID(), STAFF_SUB = UUID.randomUUID();
	private static final String ROOT = "/v1/me/adoption-notes";
	@Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired DataSource dataSource;
	@Autowired JsonMapper json; @Autowired JwtTestSupport tokens;
	@BeforeAll static void migrate() throws Exception { SchemaMigrationTest.migratePostgres(); }
	@BeforeEach void fixtures() throws Exception {
		var connection = DataSourceUtils.getConnection(dataSource);
		try { SampleDataLoader.insert(connection, SampleDataLoader.read(Path.of("sample-data/dataset.json"))); }
		finally { DataSourceUtils.releaseConnection(connection, dataSource); }
		link(USER, SUBJECT); link(OTHER, OTHER_SUB); link(STAFF, STAFF_SUB);
	}
	@Test void createReadReplaceAndClearPreserveIdentityAndExactText() throws Exception {
		assertThat(body(auth(get(ROOT), SUBJECT), 200).get("data").size()).isZero();
		mvc.perform(auth(get(path(DOG)), SUBJECT)).andExpect(status().isNotFound());
		String questions = " 밥은 언제 먹어?\n🐶 ";
		var saved = save(DOG, SUBJECT, payload(questions, "매일 두 번 산책", "{\"housingChecked\":true,\"budgetPlanned\":false}", null), 201);
		assertThat(saved.get("questions").asString()).isEqualTo(questions);
		assertThat(saved.at("/checklist/budgetPlanned").asBoolean()).isFalse();
		assertThat(saved.has("userId")).isFalse();
		assertThat(saved.has("photos")).isFalse();
		assertThat(body(auth(get(path(DOG)), SUBJECT), 200).get("data")).isEqualTo(saved);
		var changed = save(DOG, SUBJECT, payload("새 질문", "", "{}", version(saved)), 200);
		assertThat(changed.get("id")).isEqualTo(saved.get("id"));
		assertThat(changed.get("createdAt")).isEqualTo(saved.get("createdAt"));
		assertThat(changed.get("updatedAt")).isNotEqualTo(saved.get("updatedAt"));
		assertThat(changed.get("checklist").isEmpty()).isTrue();
		var empty = save(DOG, SUBJECT, payload("", "", "{}", version(changed)), 200);
		assertThat(empty.get("questions").asString()).isEmpty();
		assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.adoption_notes WHERE user_id=? AND dog_id=?", Integer.class, USER, DOG)).isEqualTo(1);
		assertThat(save(DOG, SUBJECT, payload("", "", "{}", version(empty)), 200)).isEqualTo(empty);
	}
	@Test void staleVersionsAndRepeatedCreationNeverOverwriteNewerContent() throws Exception {
		var first = save(DOG, SUBJECT, payload("처음", "", "{}", null), 201);
		var next = save(DOG, SUBJECT, payload("최신", "", "{}", version(first)), 200);
		for (String expected : Arrays.asList(null, version(first)))
			mvc.perform(auth(put(path(DOG)), SUBJECT).contentType("application/json").content(payload("덮어쓰기", "", "{}", expected)))
					.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NOTE_VERSION_CONFLICT"));
		assertThat(body(auth(get(path(DOG)), SUBJECT), 200).get("data")).isEqualTo(next);
		mvc.perform(auth(put(path(DOG2)), SUBJECT).contentType("application/json").content(payload("없는 메모", "", "{}", version(first))))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTE_NOT_FOUND"));
	}
	@Test void ownersAreIsolatedEvenForOperatorsAndShelterStaff() throws Exception {
		var original = save(DOG, SUBJECT, payload("나만의 질문", "개인 계획", "{}", null), 201);
		for (UUID subject : List.of(OTHER_SUB, STAFF_SUB)) {
			mvc.perform(auth(get(path(DOG)), subject)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTE_NOT_FOUND"));
			assertThat(body(auth(get(ROOT), subject), 200).get("data").size()).isZero();
			mvc.perform(auth(put(path(DOG)), subject).contentType("application/json").content(payload("침범", "", "{}", version(original))))
					.andExpect(status().isNotFound());
			assertThat(save(DOG, subject, payload("각자 메모", "", "{}", null), 201).get("id")).isNotEqualTo(original.get("id"));
		}
		assertThat(body(auth(get(path(DOG)), SUBJECT), 200).get("data")).isEqualTo(original);
		assertThat(body(auth(get(ROOT), SUBJECT), 200).get("data").size()).isEqualTo(1);
	}
	@ParameterizedTest @ValueSource(strings = {"dog-private", "shelter-private", "shelter-suspended", "ADOPTED", "PAUSED", "archived"})
	void existingPersonalNotesRemainReadableAndEditableWhenDogBecomesUnavailable(String state) throws Exception {
		var original = save(DOG, SUBJECT, payload("내 기록", "", "{}", null), 201);
		switch (state) {
			case "dog-private" -> jdbc.update("UPDATE shelter.dogs SET is_public=false WHERE id=?", DOG);
			case "shelter-private" -> jdbc.update("UPDATE shelter.shelters SET is_public=false WHERE id=?", SHELTER);
			case "shelter-suspended" -> jdbc.update("UPDATE shelter.shelters SET approval_status='SUSPENDED',is_public=false WHERE id=?", SHELTER);
			case "archived" -> jdbc.update("UPDATE shelter.dogs SET archived_at=now() WHERE id=?", DOG);
			default -> jdbc.update("UPDATE shelter.dogs SET adoption_status=? WHERE id=?", state, DOG);
		}
		assertThat(body(auth(get(path(DOG)), SUBJECT), 200).get("data")).isEqualTo(original);
		assertThat(body(auth(get(ROOT), SUBJECT), 200).get("data").size()).isEqualTo(1);
		assertThat(save(DOG, SUBJECT, payload("보관할 기록", "", "{}", version(original)), 200).get("questions").asString()).isEqualTo("보관할 기록");
		mvc.perform(auth(put(path(DOG)), OTHER_SUB).contentType("application/json").content(payload("새 메모", "", "{}", null)))
				.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOG_NOT_FOUND"));
	}
	@Test void privateAndMissingDogsHaveTheSameCreationError() throws Exception {
		for (UUID dog : List.of(PRIVATE, UUID.randomUUID()))
			mvc.perform(auth(put(path(dog)), SUBJECT).contentType("application/json").content(payload("질문", "", "{}", null)))
					.andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("DOG_NOT_FOUND"));
		assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.adoption_notes WHERE user_id=?", Integer.class, USER)).isZero();
		jdbc.update("UPDATE shelter.dogs SET adoption_status='IN_PROGRESS' WHERE id=?", DOG);
		save(DOG, SUBJECT, payload("", "", "{}", null), 201);
	}
	@Test void pagesAreOwnerScopedAndStableAcrossEditsAndTiedCreationTimes() throws Exception {
		var first = save(DOG, SUBJECT, payload("첫 메모", "", "{}", null), 201);
		save(DOG2, SUBJECT, payload("두 번째", "", "{}", null), 201);
		save(DOG, OTHER_SUB, payload("다른 사용자", "", "{}", null), 201);
		jdbc.update("UPDATE shelter.adoption_notes SET created_at='2026-09-01T00:00:00Z' WHERE user_id=?", USER);
		var page = body(auth(get(ROOT).param("limit", "1"), SUBJECT), 200);
		String cursor = page.get("nextCursor").asString();
		var fresh = body(auth(get(path(DOG)), SUBJECT), 200).get("data");
		save(DOG, SUBJECT, payload("수정해도 순서 유지", "", "{}", version(fresh)), 200);
		var rest = body(auth(get(ROOT).param("cursor", cursor).param("limit", "1"), SUBJECT), 200);
		assertThat(rest.get("data").size()).isEqualTo(1);
		assertThat(rest.at("/data/0/id")).isNotEqualTo(page.at("/data/0/id"));
		assertThat(rest.get("nextCursor").isNull()).isTrue();
		mvc.perform(auth(get(ROOT).param("cursor", cursor), OTHER_SUB)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
		assertThat(body(auth(get(ROOT).param("limit", "50"), SUBJECT), 200).get("data").size()).isEqualTo(2);
	}
	@ParameterizedTest @ValueSource(strings = {"disabled", "unregistered"})
	void unavailableAccountsCannotReadOrWrite(String state) throws Exception {
		var original = save(DOG, SUBJECT, payload("기존", "", "{}", null), 201);
		UUID subject = SUBJECT;
		if (state.equals("disabled")) jdbc.update("UPDATE shelter.app_users SET disabled_at=now() WHERE id=?", USER);
		else subject = UUID.randomUUID();
		for (var request : List.of(get(ROOT), get(path(DOG)), put(path(DOG)).contentType("application/json")
				.content(payload("변경", "", "{}", version(original)))))
			mvc.perform(auth(request, subject)).andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("SELECT questions FROM shelter.adoption_notes WHERE user_id=? AND dog_id=?", String.class, USER, DOG)).isEqualTo("기존");
	}
	private JsonNode save(UUID dog, UUID subject, String payload, int code) throws Exception {
		var result = mvc.perform(auth(put(path(dog)), subject).contentType("application/json").content(payload))
				.andExpect(status().is(code)).andExpect(header().string("Location", path(dog)))
				.andExpect(header().string("Cache-Control", "no-store")).andReturn();
		return json.readTree(result.getResponse().getContentAsString()).get("data");
	}
	private String payload(String questions, String care, String checklist, String expected) {
		var body = json.createObjectNode().put("questions", questions).put("carePlan", care).set("checklist", json.readTree(checklist));
		if (expected == null) body.putNull("expectedUpdatedAt"); else body.put("expectedUpdatedAt", expected);
		return body.toString();
	}
	private String version(JsonNode note) { return note.get("updatedAt").asString(); }
	private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req, UUID subject) { return req.header("Authorization", "Bearer " + tokens.token(subject)); }
	private JsonNode body(MockHttpServletRequestBuilder req, int code) throws Exception {
		return json.readTree(mvc.perform(req).andExpect(status().is(code)).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString());
	}
	private void link(UUID user, UUID subject) { jdbc.update("UPDATE shelter.app_users SET auth_provider=?,auth_subject=? WHERE id=?", tokens.properties.providerKey(), subject.toString(), user); }
	private String path(UUID dog) { return ROOT + "/" + dog; }
	private static UUID id(String prefix, int suffix) { return UUID.fromString(prefix + "00000-0000-4000-8000-" + String.format("%012d", suffix)); }
}
