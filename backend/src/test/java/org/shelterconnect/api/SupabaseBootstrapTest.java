package org.shelterconnect.api;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.shelterconnect.sample.SampleDataLoader;
import org.shelterconnect.sample.SupabaseBootstrapExporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("postgres")
class SupabaseBootstrapTest {
	@Test
	void sqlEditorBootstrapRemainsCompatibleWithFlywayAndRejectsReapplication() throws Exception {
		String url = System.getenv("TEST_DB_URL");
		SampleDataLoader.validateTarget(url);
		if (!url.endsWith("/shelter_test")) throw new IllegalArgumentException("Use the disposable shelter_test database");
		String username = System.getenv("TEST_DB_USERNAME"), password = System.getenv("TEST_DB_PASSWORD");
		String database = "shelter_bootstrap_" + UUID.randomUUID().toString().replace("-", "");
		String isolatedUrl = url.substring(0, url.lastIndexOf('/') + 1) + database;
		String sql = SupabaseBootstrapExporter.export(
				Path.of("src/main/resources/db/migration/V1__create_shelter_domain.sql"), Path.of("sample-data/dataset.json"));
		try (var admin = DriverManager.getConnection(url, username, password); var statement = admin.createStatement()) {
			statement.execute("CREATE DATABASE " + database);
			try {
				try (var connection = DriverManager.getConnection(isolatedUrl, username, password);
						var bootstrap = connection.createStatement()) {
					bootstrap.execute(sql);
					try (var rows = bootstrap.executeQuery("""
							SELECT (SELECT count(*) FROM shelter.shelters), (SELECT count(*) FROM shelter.dogs),
							       (SELECT count(*) FROM shelter.dog_observations)
							""")) {
						rows.next();
						assertThat(rows.getInt(1)).isEqualTo(2);
						assertThat(rows.getInt(2)).isEqualTo(5);
						assertThat(rows.getInt(3)).isEqualTo(25);
					}
					try (var rows = bootstrap.executeQuery("SELECT count(*) FROM pg_tables WHERE schemaname = 'shelter' AND rowsecurity")) {
						rows.next();
						assertThat(rows.getInt(1)).isEqualTo(12);
					}
					assertThatThrownBy(() -> bootstrap.execute(sql)).isInstanceOf(SQLException.class)
							.satisfies(error -> assertThat(((SQLException) error).getSQLState()).isEqualTo("42P06"));
					bootstrap.execute("ROLLBACK");
					bootstrap.execute("INSERT INTO shelter.dog_behavior_profiles(dog_id,source) VALUES ('02200000-0000-4000-8000-000000000001','AI_SUGGESTED')");
				}
				var flyway = Flyway.configure().dataSource(isolatedUrl, username, password)
						.schemas("shelter").defaultSchema("shelter").cleanDisabled(true)
						.baselineOnMigrate(false).validateMigrationNaming(true).load();
				assertThat(flyway.info().current().getVersion().toString()).isEqualTo("1");
				assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
				flyway.validate();
				assertThat(flyway.migrate().migrationsExecuted).isZero();
				assertThat(flyway.info().current().getVersion().toString()).isEqualTo("4");
				try (var connection = DriverManager.getConnection(isolatedUrl, username, password);
						var check = connection.createStatement();
						var rows = check.executeQuery("SELECT revision,status,settings::text FROM shelter.dog_behavior_profiles")) {
					rows.next();
					assertThat(rows.getInt(1)).isEqualTo(1);
					assertThat(rows.getString(2)).isEqualTo("DRAFT");
					assertThat(rows.getString(3)).isEqualTo("{}");
				}
			} finally {
				// Only the uniquely named database created by this test is removed.
				statement.execute("DROP DATABASE " + database);
			}
		}
	}
}
