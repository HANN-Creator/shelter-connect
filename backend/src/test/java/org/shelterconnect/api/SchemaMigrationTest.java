package org.shelterconnect.api;

import java.net.URI;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("postgres")
class SchemaMigrationTest {

	private static final UUID USER = id(1), STAFF = id(2), OTHER_USER = id(3);
	private static final UUID SHELTER = id(10), DOG = id(20), OTHER_DOG = id(21);
	private static final UUID OBSERVATION = id(30), OTHER_OBSERVATION = id(31);
	private static final UUID SESSION = id(40), OTHER_SESSION = id(41);
	private static final UUID QUESTION = id(50), ANSWER = id(51);
	private static String url, username, password;
	private static Flyway flyway;
	private Connection connection;

	@BeforeAll
	static void migratePostgres() throws Exception {
		url = required("TEST_DB_URL");
		username = required("TEST_DB_USERNAME");
		password = required("TEST_DB_PASSWORD");
		URI target = URI.create(url.replaceFirst("^jdbc:", ""));
		if (!"postgresql".equals(target.getScheme())
				|| !Set.of("localhost", "127.0.0.1", "[::1]").contains(target.getHost())
				|| !"/shelter_test".equals(target.getPath())) {
			throw new IllegalArgumentException("Use a disposable local PostgreSQL database named shelter_test");
		}
		try (var connection = DriverManager.getConnection(url, username, password);
				var statement = connection.createStatement()) {
			// Emulate the client-facing roles present in Supabase. No login credentials are created.
			statement.execute("""
					DO $$ BEGIN
					    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'anon') THEN
					        CREATE ROLE anon NOLOGIN;
					    END IF;
					    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'authenticated') THEN
					        CREATE ROLE authenticated NOLOGIN;
					    END IF;
					END $$;
					""");
		}
		flyway = Flyway.configure().dataSource(url, username, password)
				.schemas("shelter").defaultSchema("shelter").cleanDisabled(true)
				.baselineOnMigrate(false).validateMigrationNaming(true).load();
		flyway.migrate();
		flyway.validate();
	}

	@BeforeEach
	void createFixtures() throws Exception {
		connection = DriverManager.getConnection(url, username, password);
		connection.setAutoCommit(false);
		execute("INSERT INTO shelter.app_users(id, display_name) VALUES (?, '방문자'), (?, '담당자'), (?, '다른 방문자')",
				USER, STAFF, OTHER_USER);
		execute("INSERT INTO shelter.shelters(id, name, region) VALUES (?, '테스트 보호소', '서울')", SHELTER);
		execute("INSERT INTO shelter.dogs(id, shelter_id, name, avatar_key) VALUES (?, ?, '봄이', 'cream-floppy'), (?, ?, '보리', 'brown')",
				DOG, SHELTER, OTHER_DOG, SHELTER);
		execute("""
				INSERT INTO shelter.dog_observations(id, dog_id, category, content, observed_at, recorded_by,
				    status, confirmed_by, confirmed_at)
				VALUES (?, ?, 'PLAY', '공놀이를 좋아함', now(), ?, 'CONFIRMED', ?, now()),
				       (?, ?, 'PEOPLE', '천천히 다가가면 편안해함', now(), ?, 'CONFIRMED', ?, now())
				""", OBSERVATION, DOG, STAFF, STAFF, OTHER_OBSERVATION, OTHER_DOG, STAFF, STAFF);
		execute("INSERT INTO shelter.chat_sessions(id, user_id, dog_id) VALUES (?, ?, ?), (?, ?, ?)",
				SESSION, USER, DOG, OTHER_SESSION, OTHER_USER, OTHER_DOG);
		execute("""
				INSERT INTO shelter.chat_messages(id, session_id, dog_id, role, content, client_message_id, processing_status)
				VALUES (?, ?, ?, 'USER', '무슨 놀이를 좋아해?', 'request-1', 'PENDING')
				""", QUESTION, SESSION, DOG);
		execute("""
				INSERT INTO shelter.chat_messages(id, session_id, dog_id, role, content, reply_to_message_id, processing_status)
				VALUES (?, ?, ?, 'ASSISTANT', '공놀이를 좋아해!', ?, 'COMPLETED')
				""", ANSWER, SESSION, DOG, QUESTION);
	}

	@AfterEach
	void discardFixtures() throws Exception {
		if (connection != null) {
			try { connection.rollback(); }
			finally { connection.close(); }
		}
	}

	@Test
	void migrationCanRunAgainWithoutChangingTheSchema() {
		assertThat(flyway.migrate().migrationsExecuted).isZero();
		assertThat(flyway.info().current().getVersion().toString()).isEqualTo("1");
	}

	@Test
	void allBusinessTablesHaveRowSecurityEnabled() throws Exception {
		assertThat(number("SELECT count(*) FROM pg_tables WHERE schemaname = 'shelter' AND tablename <> 'flyway_schema_history' AND rowsecurity"))
				.isEqualTo(11);
	}

	@ParameterizedTest
	@ValueSource(strings = {"anon", "authenticated"})
	void clientRolesHaveNoSchemaOrTableAccess(String role) throws Exception {
		assertThat(number("SELECT count(*) FROM pg_tables WHERE schemaname = 'shelter' AND has_table_privilege(?, quote_ident(schemaname) || '.' || quote_ident(tablename), 'SELECT')", role)).isZero();
		assertThat(number("SELECT CASE WHEN has_schema_privilege(?, 'shelter', 'USAGE') THEN 1 ELSE 0 END", role)).isZero();
	}

	@Test
	void rowSecurityStillBlocksClientsAfterAnAccidentalTableGrant() throws Exception {
		execute("GRANT USAGE ON SCHEMA shelter TO authenticated");
		execute("GRANT SELECT, INSERT ON shelter.dogs TO authenticated");
		execute("SET LOCAL ROLE authenticated");
		assertThat(number("SELECT count(*) FROM shelter.dogs")).isZero();
		reject("42501", "INSERT INTO shelter.dogs(shelter_id, name, avatar_key) VALUES (?, '차단 대상', 'test')", SHELTER);
	}

	@Test
	void aDogMustBelongToAnExistingShelter() {
		reject("23503", "INSERT INTO shelter.dogs(shelter_id, name, avatar_key) VALUES (?, '봄이', 'test')", id(999));
	}

	@Test
	void onlyApprovedSheltersCanBePublic() {
		reject("23514", "UPDATE shelter.shelters SET is_public = true WHERE id = ?", SHELTER);
	}

	@Test
	void membershipCannotBeDuplicated() throws Exception {
		execute("INSERT INTO shelter.shelter_memberships(user_id, shelter_id, status) VALUES (?, ?, 'ACTIVE')", STAFF, SHELTER);
		reject("23505", "INSERT INTO shelter.shelter_memberships(user_id, shelter_id) VALUES (?, ?)", STAFF, SHELTER);
	}

	@Test
	void unknownBirthdayAndNeuteringRemainUnknown() throws Exception {
		assertThat(number("SELECT count(*) FROM shelter.dogs WHERE id = ? AND birth_date IS NULL AND birth_date_estimated IS NULL AND neutered IS NULL", DOG)).isEqualTo(1);
		execute("UPDATE shelter.dogs SET birth_date = '2022-01-01', birth_date_precision = 'YEAR', birth_date_estimated = true WHERE id = ?", DOG);
	}

	@ParameterizedTest
	@ValueSource(strings = {
			"birth_date = '2022-01-01'",
			"birth_date_precision = 'DAY'",
			"birth_date = 'infinity', birth_date_precision = 'DAY', birth_date_estimated = false",
			"birth_date = '2022-02-01', birth_date_precision = 'YEAR', birth_date_estimated = true",
			"birth_date = '2022-02-27', birth_date_precision = 'MONTH', birth_date_estimated = true"
	})
	void birthdayCannotClaimMorePrecisionThanItHas(String update) {
		reject("23514", "UPDATE shelter.dogs SET " + update + " WHERE id = ?", DOG);
	}

	@Test
	void confirmedObservationsNeedAConfirmationRecord() {
		reject("23514", "UPDATE shelter.dog_observations SET confirmed_by = NULL WHERE id = ?", OBSERVATION);
	}

	@Test
	void photoPositionsAreUniquePerDog() throws Exception {
		execute("INSERT INTO shelter.dog_photos(dog_id, storage_bucket, storage_key, sort_order) VALUES (?, 'dogs', 'one.jpg', 0)", DOG);
		reject("23505", "INSERT INTO shelter.dog_photos(dog_id, storage_bucket, storage_key, sort_order) VALUES (?, 'dogs', 'two.jpg', 0)", DOG);
	}

	@Test
	void photoPermissionNeedsItsEvidence() {
		reject("23514", "INSERT INTO shelter.dog_photos(dog_id, storage_bucket, storage_key, sort_order, rights_status) VALUES (?, 'dogs', 'one.jpg', 0, 'GRANTED')", DOG);
	}

	@Test
	void behaviorSettingsMustBeAnObjectAndStayDraftUntilConfirmed() throws Exception {
		execute("INSERT INTO shelter.dog_behavior_profiles(dog_id, source) VALUES (?, 'AI_SUGGESTED')", DOG);
		assertThat(number("SELECT count(*) FROM shelter.dog_behavior_profiles WHERE dog_id = ? AND status = 'DRAFT'", DOG)).isEqualTo(1);
		reject("23514", "UPDATE shelter.dog_behavior_profiles SET settings = '[]'::jsonb WHERE dog_id = ?", DOG);
	}

	@Test
	void aSessionCannotSwitchItsDogEvenBeforeAnyMessages() throws Exception {
		execute("INSERT INTO shelter.chat_sessions(id, user_id, dog_id) VALUES (?, ?, ?)", id(42), USER, DOG);
		reject("23514", "UPDATE shelter.chat_sessions SET dog_id = ? WHERE id = ?", OTHER_DOG, id(42));
	}

	@Test
	void aSessionCannotChangeItsOwner() {
		reject("23514", "UPDATE shelter.chat_sessions SET user_id = ? WHERE id = ?", OTHER_USER, SESSION);
	}

	@Test
	void aMessageCannotUseAnotherSessionsDog() {
		reject("23503", "INSERT INTO shelter.chat_messages(session_id, dog_id, role, content, client_message_id, processing_status) VALUES (?, ?, 'USER', '안녕', 'new-request', 'PENDING')", SESSION, OTHER_DOG);
	}

	@Test
	void retryingAClientRequestCannotCreateAnotherMessage() {
		reject("23505", "INSERT INTO shelter.chat_messages(session_id, dog_id, role, content, client_message_id, processing_status) VALUES (?, ?, 'USER', '안녕', 'request-1', 'PENDING')", SESSION, DOG);
	}

	@Test
	void anAnswerCanOnlyReplyToAUserMessage() {
		reject("23503", "INSERT INTO shelter.chat_messages(session_id, dog_id, role, content, reply_to_message_id, processing_status) VALUES (?, ?, 'ASSISTANT', '답변', ?, 'COMPLETED')", SESSION, DOG, ANSWER);
	}

	@Test
	void aUserMessageCanHaveOnlyOneCompletedAnswer() {
		reject("23505", "INSERT INTO shelter.chat_messages(session_id, dog_id, role, content, reply_to_message_id, processing_status) VALUES (?, ?, 'ASSISTANT', '중복 답변', ?, 'COMPLETED')", SESSION, DOG, QUESTION);
	}

	@Test
	void retryIdentityCannotBeRewritten() {
		reject("23514", "UPDATE shelter.chat_messages SET client_message_id = 'replacement' WHERE id = ?", QUESTION);
	}

	@Test
	void aCitationCannotReferenceAnotherDogsObservation() {
		reject("23503", "INSERT INTO shelter.chat_message_observations(message_id, observation_id, dog_id, observation_snapshot) VALUES (?, ?, ?, '다른 강아지 기록')", ANSWER, OTHER_OBSERVATION, DOG);
	}

	@Test
	void validConversationKeepsEvidenceAndNotesAfterAdoption() throws Exception {
		execute("INSERT INTO shelter.chat_message_observations(message_id, observation_id, dog_id, observation_snapshot) VALUES (?, ?, ?, '공놀이를 좋아함')", ANSWER, OBSERVATION, DOG);
		execute("UPDATE shelter.chat_messages SET processing_status = 'COMPLETED' WHERE id = ?", QUESTION);
		execute("INSERT INTO shelter.adoption_notes(user_id, dog_id, questions, care_plan) VALUES (?, ?, '산책은 얼마나 하나요?', '아침저녁 산책')", USER, DOG);
		execute("UPDATE shelter.dogs SET adoption_status = 'ADOPTED', archived_at = now() WHERE id = ?", DOG);
		assertThat(number("SELECT count(*) FROM shelter.chat_messages WHERE session_id = ?", SESSION)).isEqualTo(2);
		assertThat(number("SELECT count(*) FROM shelter.chat_message_observations WHERE message_id = ?", ANSWER)).isEqualTo(1);
		assertThat(number("SELECT count(*) FROM shelter.adoption_notes WHERE user_id = ? AND dog_id = ?", USER, DOG)).isEqualTo(1);
	}

	@Test
	void deletingADogCannotSilentlyDeleteItsHistory() {
		reject("23503", "DELETE FROM shelter.dogs WHERE id = ?", DOG);
	}

	@Test
	void noteOwnershipIsUniqueAndUpdateTimestampIsMaintained() throws Exception {
		execute("INSERT INTO shelter.adoption_notes(user_id, dog_id, updated_at) VALUES (?, ?, '2000-01-01 00:00:00+00')", USER, DOG);
		execute("UPDATE shelter.adoption_notes SET questions = '돌봄 질문' WHERE user_id = ? AND dog_id = ?", USER, DOG);
		assertThat(number("SELECT count(*) FROM shelter.adoption_notes WHERE user_id = ? AND updated_at > '2000-01-02 00:00:00+00'", USER)).isEqualTo(1);
		reject("23505", "INSERT INTO shelter.adoption_notes(user_id, dog_id) VALUES (?, ?)", USER, DOG);
	}

	private void execute(String sql, Object... values) throws SQLException {
		try (var statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
			statement.execute();
		}
	}

	private long number(String sql, Object... values) throws SQLException {
		try (var statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
			try (var result = statement.executeQuery()) {
				result.next();
				return result.getLong(1);
			}
		}
	}

	private void reject(String sqlState, String sql, Object... values) {
		assertThatThrownBy(() -> execute(sql, values)).isInstanceOf(SQLException.class)
				.satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo(sqlState));
	}

	private static UUID id(int value) { return new UUID(0, value); }

	private static String required(String name) {
		var value = System.getenv(name);
		if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + name + " for integrationTest");
		return value;
	}
}
