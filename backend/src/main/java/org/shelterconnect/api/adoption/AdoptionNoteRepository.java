package org.shelterconnect.api.adoption;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import static org.shelterconnect.api.adoption.AdoptionNoteResponses.*;

@Repository
public class AdoptionNoteRepository {
	private final JdbcClient jdbc;
	private final JsonMapper json;
	private final RowMapper<Note> mapper;
	public AdoptionNoteRepository(JdbcClient jdbc, JsonMapper json) {
		this.jdbc = jdbc; this.json = json; this.mapper = (rs, n) -> note(rs);
	}
	public Optional<Note> note(UUID user, UUID dog, boolean lock) {
		return jdbc.sql("SELECT * FROM shelter.adoption_notes WHERE user_id=:user AND dog_id=:dog"
				+ (lock ? " FOR UPDATE" : "")).param("user", user).param("dog", dog).query(mapper).optional();
	}
	public boolean lockAvailableDog(UUID dog) {
		return jdbc.sql("""
				SELECT d.id FROM shelter.dogs d JOIN shelter.shelters s ON s.id=d.shelter_id
				WHERE d.id=:dog AND s.approval_status='APPROVED' AND s.is_public AND d.is_public
				AND d.archived_at IS NULL AND d.adoption_status IN ('AVAILABLE','IN_PROGRESS') FOR SHARE OF s,d
				""").param("dog", dog).query(UUID.class).optional().isPresent();
	}
	public Note create(UUID user, UUID dog, AdoptionNoteInput.Save input) {
		return jdbc.sql("""
				INSERT INTO shelter.adoption_notes(user_id,dog_id,questions,care_plan,checklist,created_at,updated_at)
				VALUES (:user,:dog,:questions,:care,CAST(:checks AS jsonb),clock_timestamp(),clock_timestamp()) RETURNING *
				""").param("user", user).param("dog", dog).param("questions", input.questions())
				.param("care", input.carePlan()).param("checks", json.writeValueAsString(input.checklist())).query(mapper).single();
	}
	public Note update(UUID user, UUID dog, AdoptionNoteInput.Save input) {
		return jdbc.sql("""
				UPDATE shelter.adoption_notes SET questions=:questions,care_plan=:care,checklist=CAST(:checks AS jsonb)
				WHERE user_id=:user AND dog_id=:dog AND updated_at=:expected RETURNING *
				""").param("user", user).param("dog", dog).param("questions", input.questions())
				.param("care", input.carePlan()).param("checks", json.writeValueAsString(input.checklist()))
				.param("expected", input.expectedUpdatedAt().atOffset(ZoneOffset.UTC)).query(mapper).optional()
				.orElseThrow(AdoptionNoteException::conflict);
	}
	public List<Note> notes(UUID user, AdoptionNoteInput.Cursor after, int count) {
		var query = jdbc.sql("SELECT * FROM shelter.adoption_notes WHERE user_id=:user"
				+ (after == null ? "" : " AND (created_at,id)<(:at,:last)")
				+ " ORDER BY created_at DESC,id DESC LIMIT :count").param("user", user).param("count", count);
		if (after != null) query = query.param("at", after.at().atOffset(ZoneOffset.UTC)).param("last", after.id());
		return query.query(mapper).list();
	}
	private Note note(ResultSet rs) throws SQLException {
		Map<String, Boolean> checks = new TreeMap<>();
		var stored = json.readTree(rs.getString("checklist"));
		if (!stored.isObject()) throw new IllegalStateException("Invalid stored checklist");
		for (var entry : stored.properties()) {
			if (!entry.getValue().isBoolean()) throw new IllegalStateException("Invalid stored checklist");
			checks.put(entry.getKey(), entry.getValue().asBoolean());
		}
		return new Note(rs.getObject("id", UUID.class), rs.getObject("dog_id", UUID.class), rs.getString("questions"),
				rs.getString("care_plan"), Map.copyOf(checks), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
				rs.getObject("updated_at", OffsetDateTime.class).toInstant());
	}
}
