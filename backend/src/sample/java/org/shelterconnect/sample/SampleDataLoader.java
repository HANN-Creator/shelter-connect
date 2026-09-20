package org.shelterconnect.sample;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tools.jackson.databind.json.JsonMapper;

/** Explicit local-only loader. Running the server never loads these fixtures. */
public final class SampleDataLoader {
	private SampleDataLoader() {}

	public static void main(String[] args) throws Exception {
		if (args.length != 1) throw new IllegalArgumentException("Pass the sample dataset path");
		String url = required("DB_URL");
		validateTarget(url);
		Dataset data = read(Path.of(args[0]));
		try (var connection = DriverManager.getConnection(url, required("DB_USERNAME"), required("DB_PASSWORD"))) {
			connection.setAutoCommit(false);
			try {
				Map<String, Integer> inserted = insert(connection, data);
				connection.commit();
				System.out.println("Fictional sample rows inserted: " + inserted);
			} catch (Exception error) {
				connection.rollback();
				throw error;
			}
		}
	}

	public static void validateTarget(String url) {
		// No URL options: JDBC query parameters must not override the checked destination.
		if (url == null || !url.matches("jdbc:postgresql://(localhost|127\\.0\\.0\\.1|\\[::1\\])(:[0-9]{1,5})?/(shelter_connect|shelter_test)")) {
			throw new IllegalArgumentException("Use a local PostgreSQL database named shelter_connect or shelter_test, without URL options");
		}
	}

	public static Dataset read(Path path) {
		Dataset data = JsonMapper.builder().build().readValue(path.toFile(), Dataset.class);
		if (data.version() != 1 || !data.fictional()) {
			throw new IllegalArgumentException("Expected fictional sample dataset version 1");
		}
		return data;
	}

	/** Caller owns the transaction. A failed load rolls back only this load's changes. */
	public static Map<String, Integer> insert(Connection connection, Dataset data) throws SQLException {
		if (connection.getAutoCommit()) throw new IllegalArgumentException("Start a transaction before loading samples");
		var savepoint = connection.setSavepoint();
		try {
			var inserted = new LinkedHashMap<String, Integer>();
			int count = 0;
			for (User row : data.users()) {
				count += execute(connection, """
						INSERT INTO shelter.app_users(id, display_name, role) VALUES (?, ?, ?)
						ON CONFLICT (id) DO NOTHING
						""", id(row.id()), row.displayName(), row.role());
			}
			inserted.put("users", count);
			count = 0;
			for (Shelter row : data.shelters()) {
				count += execute(connection, """
						INSERT INTO shelter.shelters(id, name, region, latitude, longitude, map_key,
						    approval_status, is_public, reviewed_by, reviewed_at)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
						""", id(row.id()), row.name(), row.region(), row.latitude(), row.longitude(), row.mapKey(),
						row.approvalStatus(), row.isPublic(), id(row.reviewedBy()), time(row.reviewedAt()));
			}
			inserted.put("shelters", count);
			count = 0;
			for (Membership row : data.memberships()) {
				count += execute(connection, """
						INSERT INTO shelter.shelter_memberships(id, user_id, shelter_id, role, status)
						VALUES (?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
						""", id(row.id()), id(row.userId()), id(row.shelterId()), row.role(), row.status());
			}
			inserted.put("memberships", count);
			count = 0;
			for (Dog row : data.dogs()) {
				count += execute(connection, """
						INSERT INTO shelter.dogs(id, shelter_id, name, sex, breed, birth_date, birth_date_precision,
						    birth_date_estimated, weight_kg, neutered, adoption_status, is_public,
						    avatar_key, trait_labels, introduction)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
						""", id(row.id()), id(row.shelterId()), row.name(), row.sex(), row.breed(), date(row.birthDate()),
						row.birthDatePrecision(), row.birthDateEstimated(), row.weightKg(), row.neutered(),
						row.adoptionStatus(), row.isPublic(), row.avatarKey(), row.traitLabels(), row.introduction());
			}
			inserted.put("dogs", count);
			count = 0;
			for (Observation row : data.observations()) {
				count += execute(connection, """
						INSERT INTO shelter.dog_observations(id, dog_id, category, content, observed_at,
						    recorded_by, source_note, status, confirmed_by, confirmed_at)
						VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) ON CONFLICT (id) DO NOTHING
						""", id(row.id()), id(row.dogId()), row.category(), row.content(), time(row.observedAt()),
						id(row.recordedBy()), row.sourceNote(), row.status(), id(row.confirmedBy()), time(row.confirmedAt()));
			}
			inserted.put("observations", count);
			connection.releaseSavepoint(savepoint);
			return inserted;
		} catch (SQLException | RuntimeException error) {
			connection.rollback(savepoint);
			connection.releaseSavepoint(savepoint);
			throw error;
		}
	}

	private static int execute(Connection connection, String sql, Object... values) throws SQLException {
		try (var statement = connection.prepareStatement(sql)) {
			for (int index = 0; index < values.length; index++) {
				Object value = values[index];
				if (value instanceof List<?> list) {
					statement.setArray(index + 1, connection.createArrayOf("text", list.toArray()));
				} else {
					statement.setObject(index + 1, value);
				}
			}
			return statement.executeUpdate();
		}
	}

	private static UUID id(String value) { return value == null ? null : UUID.fromString(value); }
	private static LocalDate date(String value) { return value == null ? null : LocalDate.parse(value); }
	private static OffsetDateTime time(String value) { return value == null ? null : OffsetDateTime.parse(value); }

	private static String required(String name) {
		String value = System.getenv(name);
		if (value == null || value.isBlank()) throw new IllegalArgumentException("Set " + name + " before loading samples");
		return value;
	}

	public record Dataset(int version, boolean fictional, List<User> users, List<Shelter> shelters,
			List<Membership> memberships, List<Dog> dogs, List<Observation> observations) {}
	public record User(String id, String displayName, String role) {}
	public record Shelter(String id, String name, String region, BigDecimal latitude, BigDecimal longitude,
			String mapKey, String approvalStatus, boolean isPublic, String reviewedBy, String reviewedAt) {}
	public record Membership(String id, String userId, String shelterId, String role, String status) {}
	public record Dog(String id, String shelterId, String name, String sex, String breed, String birthDate,
			String birthDatePrecision, Boolean birthDateEstimated, BigDecimal weightKg, Boolean neutered,
			String adoptionStatus, boolean isPublic, String avatarKey, List<String> traitLabels, String introduction) {}
	public record Observation(String id, String dogId, String category, String content, String observedAt,
			String recordedBy, String sourceNote, String status, String confirmedBy, String confirmedAt) {}
}
