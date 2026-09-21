package org.shelterconnect.api;

import java.nio.file.Path;
import java.sql.*;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.shelterconnect.sample.*;
import static org.assertj.core.api.Assertions.*;

@Tag("postgres")
class SupabaseUpgradePostgresTest {
	@Test void upgradePreservesDataAndFlywayAcceptsItsChecksumsWithoutReapplying() throws Exception {
		withDatabase((url,user,password,statement)->{
			statement.execute("INSERT INTO shelter.dog_behavior_profiles(dog_id,source) VALUES ('02200000-0000-4000-8000-000000000001','AI_SUGGESTED')");
			String sql=SupabaseUpgradeExporter.export(Path.of("src/main/resources/db/migration"));
			statement.execute(sql);
			assertThat(number(statement,"SELECT count(*) FROM shelter.dogs")).isEqualTo(5);
			assertThat(number(statement,"SELECT count(*) FROM shelter.dog_observations")).isEqualTo(25);
			assertThat(number(statement,"SELECT count(*) FROM shelter.dog_behavior_profiles WHERE revision=1 AND settings='{}'::jsonb AND status='DRAFT'")).isEqualTo(1);
			var flyway=Flyway.configure().dataSource(url,user,password).schemas("shelter").defaultSchema("shelter").cleanDisabled(true).load();
			flyway.validate();assertThat(flyway.info().current().getVersion().toString()).isEqualTo("3");
			assertThat(flyway.migrate().migrationsExecuted).isZero();
			assertThatThrownBy(()->statement.execute(sql)).isInstanceOf(SQLException.class)
				.satisfies(ex->assertThat(((SQLException)ex).getSQLState()).isEqualTo("P0001"));
			statement.execute("ROLLBACK");
			assertThat(number(statement,"SELECT count(*) FROM shelter.flyway_schema_history WHERE version IN ('2','3') AND success")).isEqualTo(2);
		});
	}
	@Test void wrongHistoryAndV3FailureCannotPartiallyApplyV2() throws Exception {
		withDatabase((url,user,password,statement)->{
			String sql=SupabaseUpgradeExporter.export(Path.of("src/main/resources/db/migration"));
			statement.execute("UPDATE shelter.flyway_schema_history SET success=false WHERE version='1'");
			assertThatThrownBy(()->statement.execute(sql)).isInstanceOf(SQLException.class)
				.satisfies(ex->assertThat(((SQLException)ex).getSQLState()).isEqualTo("P0001"));
			statement.execute("ROLLBACK");statement.execute("UPDATE shelter.flyway_schema_history SET success=true WHERE version='1'");
			// Simulate an unexpected partial/manual V3 change. The full upgrade must abort, including earlier V2 DDL/history.
			statement.execute("ALTER TABLE shelter.dog_behavior_profiles ADD COLUMN revision integer");
			assertThatThrownBy(()->statement.execute(sql)).isInstanceOf(SQLException.class)
				.satisfies(ex->assertThat(((SQLException)ex).getSQLState()).isEqualTo("42701"));
			statement.execute("ROLLBACK");
			assertThat(number(statement,"SELECT count(*) FROM information_schema.columns WHERE table_schema='shelter' AND table_name='chat_messages' AND column_name='generation_token'")).isZero();
			assertThat(number(statement,"SELECT count(*) FROM shelter.flyway_schema_history WHERE version IN ('2','3')")).isZero();
			assertThat(number(statement,"SELECT count(*) FROM shelter.dogs")).isEqualTo(5);
		});
	}
	private interface Check { void run(String url,String user,String password,Statement statement) throws Exception; }
	private void withDatabase(Check check) throws Exception {
		String base=System.getenv("TEST_DB_URL");SampleDataLoader.validateTarget(base);
		if(!base.endsWith("/shelter_test"))throw new IllegalArgumentException("Use disposable shelter_test");
		String user=System.getenv("TEST_DB_USERNAME"),password=System.getenv("TEST_DB_PASSWORD");
		String db="shelter_upgrade_"+UUID.randomUUID().toString().replace("-","");
		String url=base.substring(0,base.lastIndexOf('/')+1)+db;
		try(var admin=DriverManager.getConnection(base,user,password);var ddl=admin.createStatement()) {
			ddl.execute("CREATE DATABASE "+db);
			try {
				try(var connection=DriverManager.getConnection(url,user,password);var statement=connection.createStatement()) {
					statement.execute(SupabaseBootstrapExporter.export(Path.of("src/main/resources/db/migration/V1__create_shelter_domain.sql"),Path.of("sample-data/dataset.json")));
					check.run(url,user,password,statement);
				}
			} finally { ddl.execute("DROP DATABASE "+db); }
		}
	}
	private long number(Statement statement,String sql) throws SQLException {try(var rows=statement.executeQuery(sql)){rows.next();return rows.getLong(1);}}
}
