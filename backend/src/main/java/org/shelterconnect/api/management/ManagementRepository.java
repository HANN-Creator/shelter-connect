package org.shelterconnect.api.management;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.SqlArrayValue;
import org.springframework.stereotype.Repository;
import static org.shelterconnect.api.management.ManagementResponses.*;

@Repository
public class ManagementRepository {
	private static final RowMapper<Dog> DOG_ROW = (rs, row) -> new Dog(uuid(rs, "id"), uuid(rs, "shelter_id"), "DOG",
			rs.getString("name"), rs.getString("sex"), rs.getString("breed"), rs.getObject("birth_date", LocalDate.class),
			rs.getString("birth_date_precision"), rs.getObject("birth_date_estimated", Boolean.class), rs.getBigDecimal("weight_kg"),
			rs.getObject("neutered", Boolean.class), rs.getString("adoption_status"), rs.getBoolean("is_public"),
			rs.getString("avatar_key"), labels(rs), rs.getString("introduction"), time(rs, "archived_at"), time(rs, "created_at"), time(rs, "updated_at"));
	private static final RowMapper<Observation> OBS_ROW = (rs, row) -> new Observation(uuid(rs, "id"), uuid(rs, "dog_id"),
			rs.getString("category"), rs.getString("content"), time(rs, "observed_at"), uuid(rs, "recorded_by"),
			rs.getString("source_note"), rs.getString("status"), uuid(rs, "confirmed_by"), time(rs, "confirmed_at"), time(rs, "created_at"), time(rs, "updated_at"));
	private final JdbcClient jdbc;
	public ManagementRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

	public List<Dog> dogs(UUID shelterId, UUID after, int count) {
		var query = jdbc.sql("SELECT * FROM shelter.dogs WHERE shelter_id = :shelter AND archived_at IS NULL"
				+ (after == null ? "" : " AND id > :after") + " ORDER BY id LIMIT :count")
				.param("shelter", shelterId).param("count", count);
		if (after != null) query = query.param("after", after);
		return query.query(DOG_ROW).list();
	}
	public Optional<Dog> dog(UUID id, UUID shelterId) {
		return jdbc.sql("SELECT * FROM shelter.dogs WHERE id = :id AND shelter_id = :shelter")
				.param("id", id).param("shelter", shelterId).query(DOG_ROW).optional();
	}
	public Dog createDog(UUID shelterId, DogFields values) {
		UUID id = dogValues(jdbc.sql("""
				INSERT INTO shelter.dogs(shelter_id, name, sex, breed, birth_date, birth_date_precision,
				birth_date_estimated, weight_kg, neutered, adoption_status, is_public, avatar_key, trait_labels, introduction)
				VALUES (:shelter, :name, :sex, :breed, :birth, :precision, :estimated, :weight, :neutered,
				:status, :public, :avatar, :labels, :introduction) RETURNING id
				"""), values).param("shelter", shelterId).query(UUID.class).single();
		return dog(id, shelterId).orElseThrow();
	}
	public Dog updateDog(Dog current, DogFields values) {
		int changed = dogValues(jdbc.sql("""
				UPDATE shelter.dogs SET name=:name, sex=:sex, breed=:breed, birth_date=:birth,
				birth_date_precision=:precision, birth_date_estimated=:estimated, weight_kg=:weight,
				neutered=:neutered, adoption_status=:status, is_public=:public, avatar_key=:avatar,
				trait_labels=:labels, introduction=:introduction
				WHERE id=:id AND shelter_id=:shelter AND updated_at=:version
				"""), values).param("id", current.id()).param("shelter", current.shelterId())
				.param("version", utc(current.updatedAt())).update();
		if (changed != 1) throw ManagementException.conflict("STALE_RESOURCE", "다시 조회한 뒤 수정해 주세요.");
		return dog(current.id(), current.shelterId()).orElseThrow();
	}
	public List<Observation> observations(UUID dogId, UUID shelterId, UUID after, int count) {
		var query = jdbc.sql("SELECT o.* FROM shelter.dog_observations o JOIN shelter.dogs d ON d.id=o.dog_id"
				+ " WHERE o.dog_id=:dog AND d.shelter_id=:shelter" + (after == null ? "" : " AND o.id > :after") + " ORDER BY o.id LIMIT :count")
				.param("dog", dogId).param("shelter", shelterId).param("count", count);
		if (after != null) query = query.param("after", after);
		return query.query(OBS_ROW).list();
	}
	public Optional<Observation> lockObservation(UUID dogId, UUID id) {
		return jdbc.sql("SELECT * FROM shelter.dog_observations WHERE dog_id=:dog AND id=:id FOR UPDATE")
				.param("dog", dogId).param("id", id).query(OBS_ROW).optional();
	}
	public Observation createObservation(UUID dogId, UUID author, ObservationFields fields) {
		return observationValues(jdbc.sql("""
				INSERT INTO shelter.dog_observations(dog_id, category, content, observed_at, recorded_by,
				source_note, status, confirmed_by, confirmed_at)
				VALUES (:dog, :category, :content, :observed, :author, :source, :status, :confirmer, :confirmed)
				RETURNING *
				"""), fields).param("dog", dogId).param("author", author)
				.param("confirmer", fields.status().equals("CONFIRMED") ? author : null)
				.param("confirmed", fields.status().equals("CONFIRMED") ? OffsetDateTime.now(ZoneOffset.UTC) : null)
				.query(OBS_ROW).single();
	}
	public Observation updateObservation(Observation old, UUID actor, ObservationFields fields) {
		boolean confirming = fields.status().equals("CONFIRMED") && !old.status().equals("CONFIRMED");
		return observationValues(jdbc.sql("""
				UPDATE shelter.dog_observations SET category=:category, content=:content, observed_at=:observed,
				source_note=:source, status=:status, confirmed_by=:confirmer, confirmed_at=:confirmed
				WHERE id=:id AND dog_id=:dog AND updated_at=:version RETURNING *
				"""), fields).param("id", old.id()).param("dog", old.dogId()).param("version", utc(old.updatedAt()))
				.param("confirmer", confirming ? actor : old.confirmedBy())
				.param("confirmed", confirming ? OffsetDateTime.now(ZoneOffset.UTC) : utc(old.confirmedAt()))
				.query(OBS_ROW).optional().orElseThrow(() -> ManagementException.conflict("STALE_RESOURCE", "다시 조회한 뒤 수정해 주세요."));
	}

	private JdbcClient.StatementSpec dogValues(JdbcClient.StatementSpec sql, DogFields v) {
		return sql.param("name", v.name()).param("sex", v.sex()).param("breed", v.breed()).param("birth", v.birthDate())
				.param("precision", v.birthDatePrecision()).param("estimated", v.birthDateEstimated()).param("weight", v.weightKg())
				.param("neutered", v.neutered()).param("status", v.adoptionStatus()).param("public", v.isPublic())
				.param("avatar", v.avatarKey()).param("labels", new SqlArrayValue("text", v.traitLabels().toArray())).param("introduction", v.introduction());
	}
	private JdbcClient.StatementSpec observationValues(JdbcClient.StatementSpec sql, ObservationFields v) {
		return sql.param("category", v.category()).param("content", v.content()).param("observed", utc(v.observedAt()))
				.param("source", v.sourceNote()).param("status", v.status());
	}
	private static UUID uuid(ResultSet rs, String column) throws SQLException { return rs.getObject(column, UUID.class); }
	private static OffsetDateTime utc(Instant value) { return value == null ? null : value.atOffset(ZoneOffset.UTC); }
	private static Instant time(ResultSet rs, String column) throws SQLException {
		var value = rs.getObject(column, OffsetDateTime.class); return value == null ? null : value.toInstant();
	}
	private static List<String> labels(ResultSet rs) throws SQLException {
		var array = rs.getArray("trait_labels");
		try { return List.copyOf(Arrays.asList((String[]) array.getArray())); } finally { array.free(); }
	}
}
