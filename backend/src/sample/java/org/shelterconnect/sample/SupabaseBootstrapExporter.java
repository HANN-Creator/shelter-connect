package org.shelterconnect.sample;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.flywaydb.core.api.Location;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resource.filesystem.FileSystemResource;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Exports a reviewed, one-time SQL Editor bootstrap; never connects to a database. */
public final class SupabaseBootstrapExporter {
	private SupabaseBootstrapExporter() {}

	public static void main(String[] args) throws IOException {
		if (args.length != 3) throw new IllegalArgumentException("Pass migration, dataset and output paths");
		Path output = Path.of(args[2]);
		Files.createDirectories(output.toAbsolutePath().getParent());
		Files.writeString(output, export(Path.of(args[0]), Path.of(args[1])));
		System.out.println("Review the SQL before running it in the selected project's SQL Editor: " + output);
	}

	public static String export(Path migration, Path dataset) throws IOException {
		SampleDataLoader.read(dataset); // Require the supported, fictional dataset version.
		var resource = new FileSystemResource(new Location("filesystem:" + migration.toAbsolutePath().getParent()),
				migration.toAbsolutePath().toString(), StandardCharsets.UTF_8, false);
		int checksum = ChecksumCalculator.calculate(resource);
		var sql = new StringBuilder("""
				-- One-time setup for the user-selected, empty shelter-connect development project.
				-- Source: V1__create_shelter_domain.sql + sample-data/dataset.json.
				-- Existing shelter schema makes this fail; nothing is dropped or overwritten.
				BEGIN;
				SET LOCAL lock_timeout = '5s';
				SET LOCAL statement_timeout = '30s';
				SET LOCAL standard_conforming_strings = on;
				CREATE SCHEMA shelter;
				-- Flyway 12.4 PostgreSQL history format. The SQL and history commit together.
				CREATE TABLE shelter.flyway_schema_history (
				    installed_rank integer NOT NULL,
				    version varchar(50),
				    description varchar(200) NOT NULL,
				    type varchar(20) NOT NULL,
				    script varchar(1000) NOT NULL,
				    checksum integer,
				    installed_by varchar(100) NOT NULL,
				    installed_on timestamp NOT NULL DEFAULT now(),
				    execution_time integer NOT NULL,
				    success boolean NOT NULL,
				    CONSTRAINT flyway_schema_history_pk PRIMARY KEY (installed_rank)
				);
				CREATE INDEX flyway_schema_history_s_idx ON shelter.flyway_schema_history(success);
				""");
		sql.append(Files.readString(migration)).append('\n');
		sql.append("""
				INSERT INTO shelter.flyway_schema_history
				(installed_rank, version, description, type, script, checksum, installed_by, execution_time, success)
				VALUES (0, NULL, '<< Flyway Schema Creation >>', 'SCHEMA', '"shelter"', NULL, current_user, 0, true),
				       (1, '1', 'create shelter domain', 'SQL', 'V1__create_shelter_domain.sql', %d, current_user,
				        (extract(epoch FROM (clock_timestamp() - transaction_timestamp())) * 1000)::integer, true);
				""".formatted(checksum));
		Map<String, Object> data = JsonMapper.builder().build().readValue(dataset.toFile(), new TypeReference<>() {});
		appendRows(sql, data, "users", "app_users");
		appendRows(sql, data, "shelters", "shelters");
		appendRows(sql, data, "memberships", "shelter_memberships");
		appendRows(sql, data, "dogs", "dogs");
		appendRows(sql, data, "observations", "dog_observations");
		sql.append("""
				DO $$ BEGIN
				    IF (SELECT count(*) FROM shelter.shelters) <> 2
				       OR (SELECT count(*) FROM shelter.dogs) <> 5
				       OR (SELECT count(*) FROM shelter.dog_observations) <> 25 THEN
				        RAISE EXCEPTION 'Unexpected sample counts; bootstrap cancelled';
				    END IF;
				END $$;
				COMMIT;
				SELECT (SELECT count(*) FROM shelter.shelters) AS shelters,
				       (SELECT count(*) FROM shelter.dogs) AS dogs,
				       (SELECT count(*) FROM shelter.dog_observations) AS observations;
				""");
		return sql.toString();
	}

	private static void appendRows(StringBuilder sql, Map<String, Object> data, String key, String table) {
		for (Object item : (List<?>) data.get(key)) {
			Map<?, ?> row = (Map<?, ?>) item;
			String columns = row.keySet().stream().map(Object::toString).map(SupabaseBootstrapExporter::column)
					.collect(Collectors.joining(", "));
			String values = row.values().stream().map(SupabaseBootstrapExporter::literal).collect(Collectors.joining(", "));
			sql.append("INSERT INTO shelter.").append(table).append(" (").append(columns)
					.append(") VALUES (").append(values).append(");\n");
		}
	}

	private static String column(String key) {
		if (!key.matches("[a-z][a-zA-Z]*")) throw new IllegalArgumentException("Unexpected sample field name");
		return key.replaceAll("([A-Z])", "_$1").toLowerCase(java.util.Locale.ROOT);
	}

	private static String literal(Object value) {
		if (value == null) return "NULL";
		if (value instanceof Boolean || value instanceof Number) return value.toString();
		if (value instanceof String text) return "'" + text.replace("'", "''") + "'";
		if (value instanceof List<?> list) {
			return "ARRAY[" + list.stream().map(SupabaseBootstrapExporter::literal).collect(Collectors.joining(", ")) + "]::text[]";
		}
		throw new IllegalArgumentException("Unexpected sample value type");
	}
}
