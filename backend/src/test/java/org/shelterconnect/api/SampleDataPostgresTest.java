package org.shelterconnect.api;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.shelterconnect.sample.SampleDataLoader;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("postgres")
class SampleDataPostgresTest {
	private final SampleDataLoader.Dataset data = SampleDataLoader.read(Path.of("sample-data/dataset.json"));
	private Connection connection;

	@BeforeAll
	static void prepareSchema() throws Exception {
		SchemaMigrationTest.migratePostgres();
	}

	@BeforeEach
	void openTransaction() throws Exception {
		connection = DriverManager.getConnection(System.getenv("TEST_DB_URL"),
				System.getenv("TEST_DB_USERNAME"), System.getenv("TEST_DB_PASSWORD"));
		connection.setAutoCommit(false);
	}

	@AfterEach
	void discardFixtures() throws Exception {
		if (connection != null) {
			try { connection.rollback(); }
			finally { connection.close(); }
		}
	}

	@Test
	void loadsTheCompleteDatasetWithValidShelterOwnership() throws Exception {
		assertThat(SampleDataLoader.insert(connection, data))
				.containsEntry("users", 4).containsEntry("shelters", 2).containsEntry("memberships", 2)
				.containsEntry("dogs", 5).containsEntry("observations", 25);
		assertThat(number("SELECT count(*) FROM shelter.dogs WHERE shelter_id = ?", data.shelters().getFirst().id())).isEqualTo(3);
		assertThat(number("SELECT count(*) FROM shelter.dogs WHERE shelter_id = ?", data.shelters().getLast().id())).isEqualTo(2);
		assertThat(number("""
				SELECT count(*) FROM shelter.dog_observations o
				JOIN shelter.dogs d ON d.id = o.dog_id
				JOIN shelter.shelter_memberships m ON m.shelter_id = d.shelter_id AND m.user_id = o.recorded_by
				WHERE m.status = 'ACTIVE'
				""")).isEqualTo(25);
		assertThat(number("""
				SELECT count(*) FROM shelter.dog_observations o
				JOIN shelter.dogs d ON d.id = o.dog_id
				JOIN shelter.shelter_memberships m ON m.shelter_id = d.shelter_id AND m.user_id = o.confirmed_by
				WHERE o.status = 'CONFIRMED' AND m.status = 'ACTIVE'
				""")).isEqualTo(20);
		assertThat(number("SELECT count(*) FROM shelter.shelters WHERE contact_phone IS NOT NULL OR website_url IS NOT NULL")).isZero();
		assertThat(number("SELECT count(*) FROM shelter.app_users WHERE auth_subject IS NOT NULL OR auth_provider IS NOT NULL")).isZero();
		assertThat(number("SELECT count(*) FROM shelter.dog_photos")).isZero();
	}

	@Test
	void loadingTwicePreservesEditsAndDoesNotDuplicateRows() throws Exception {
		SampleDataLoader.insert(connection, data);
		try (var statement = connection.prepareStatement("UPDATE shelter.dogs SET introduction = '수정한 소개' WHERE id = ?")) {
			statement.setObject(1, UUID.fromString(data.dogs().getFirst().id()));
			statement.executeUpdate();
		}
		assertThat(SampleDataLoader.insert(connection, data).values()).allMatch(count -> count == 0);
		assertThat(number("SELECT count(*) FROM shelter.dogs")).isEqualTo(5);
		assertThat(number("SELECT count(*) FROM shelter.dog_observations")).isEqualTo(25);
		assertThat(number("SELECT count(*) FROM shelter.dogs WHERE introduction = '수정한 소개'")).isEqualTo(1);
	}

	@Test
	void reloadAddsOnlyMissingRowsAndKeepsUnrelatedData() throws Exception {
		SampleDataLoader.insert(connection, data);
		try (var statement = connection.createStatement()) {
			statement.executeUpdate("INSERT INTO shelter.app_users(display_name) VALUES ('별도 테스트 사용자')");
			statement.executeUpdate("DELETE FROM shelter.dog_observations WHERE status = 'RETRACTED'");
		}
		assertThat(SampleDataLoader.insert(connection, data)).containsEntry("observations", 1).containsEntry("dogs", 0);
		assertThat(number("SELECT count(*) FROM shelter.app_users WHERE display_name = '별도 테스트 사용자'")).isEqualTo(1);
	}

	@Test
	void aLateForeignKeyFailureRollsBackTheWholeLoad() throws Exception {
		try (var statement = connection.createStatement()) {
			statement.executeUpdate("INSERT INTO shelter.app_users(display_name) VALUES ('먼저 있던 사용자')");
		}
		var observations = new ArrayList<>(data.observations());
		var row = observations.getLast();
		observations.set(observations.size() - 1, new SampleDataLoader.Observation(row.id(), UUID.randomUUID().toString(),
				row.category(), row.content(), row.observedAt(), row.recordedBy(), row.sourceNote(), row.status(),
				row.confirmedBy(), row.confirmedAt()));
		var broken = new SampleDataLoader.Dataset(data.version(), data.fictional(), data.users(), data.shelters(),
				data.memberships(), data.dogs(), observations);
		assertThatThrownBy(() -> SampleDataLoader.insert(connection, broken)).isInstanceOf(SQLException.class)
				.satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("23503"));
		assertThat(number("SELECT count(*) FROM shelter.dogs")).isZero();
		assertThat(number("SELECT count(*) FROM shelter.shelters")).isZero();
		assertThat(number("SELECT count(*) FROM shelter.dog_observations")).isZero();
		assertThat(number("SELECT count(*) FROM shelter.app_users")).isEqualTo(1);
	}

	private long number(String sql, String... ids) throws SQLException {
		try (var statement = connection.prepareStatement(sql)) {
			for (int i = 0; i < ids.length; i++) statement.setObject(i + 1, UUID.fromString(ids[i]));
			try (var result = statement.executeQuery()) {
				result.next();
				return result.getLong(1);
			}
		}
	}
}
