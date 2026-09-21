package org.shelterconnect.api.behavior;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import static org.shelterconnect.api.behavior.BehaviorTypes.*;

@Repository
public class BehaviorRepository {
	private final JdbcClient jdbc;
	private final JsonMapper json;
	public BehaviorRepository(JdbcClient jdbc,JsonMapper json) { this.jdbc=jdbc;this.json=json; }
	public Optional<Profile> profile(UUID dog) {
		return jdbc.sql("SELECT * FROM shelter.dog_behavior_profiles WHERE dog_id=:dog").param("dog",dog)
				.query((rs,n)->new Profile(dog,rs.getInt("schema_version"),rs.getInt("revision"),json.readTree(rs.getString("settings")),
						rs.getString("source"),rs.getString("status"),evidence(dog),rs.getObject("confirmed_by",UUID.class),time(rs,"confirmed_at"),time(rs,"updated_at"))).optional();
	}
	private List<UUID> evidence(UUID dog) {
		return jdbc.sql("SELECT observation_id FROM shelter.dog_behavior_evidence WHERE dog_id=:dog ORDER BY observation_id")
				.param("dog",dog).query(UUID.class).list();
	}
	public boolean evidenceValid(UUID dog,List<UUID> ids,boolean lock) {
		if(ids.isEmpty()) return false;
		var found=jdbc.sql("SELECT id FROM shelter.dog_observations WHERE dog_id=:dog AND id IN (:ids) AND status='CONFIRMED'"
				+" ORDER BY id"+(lock?" FOR SHARE":"")).param("dog",dog).param("ids",ids).query(UUID.class).list();
		return new HashSet<>(found).equals(new HashSet<>(ids));
	}
	void save(UUID dog,Save save) {
		// The caller holds the dog lock, including for the first insert, to serialize the entire profile and evidence set.
		var sql=save.expectedRevision()==0 ? jdbc.sql("""
				INSERT INTO shelter.dog_behavior_profiles(dog_id,schema_version,settings,source,status)
				VALUES (:dog,1,CAST(:settings AS jsonb),:source,'DRAFT')
				""") : jdbc.sql("""
				UPDATE shelter.dog_behavior_profiles SET schema_version=1,settings=CAST(:settings AS jsonb),source=:source,
				status='DRAFT',confirmed_by=NULL,confirmed_at=NULL,revision=revision+1 WHERE dog_id=:dog AND revision=:expected
				""").param("expected",save.expectedRevision());
		if(sql.param("dog",dog).param("settings",json.writeValueAsString(save.settings())).param("source",save.source()).update()!=1) throw BehaviorException.stale();
		jdbc.sql("DELETE FROM shelter.dog_behavior_evidence WHERE dog_id=:dog").param("dog",dog).update();
		for(UUID id:save.evidence()) jdbc.sql("INSERT INTO shelter.dog_behavior_evidence(dog_id,observation_id) VALUES (:dog,:id)")
				.param("dog",dog).param("id",id).update();
	}
	void confirm(UUID dog,int revision,UUID actor) {
		if(jdbc.sql("""
				UPDATE shelter.dog_behavior_profiles SET status='CONFIRMED',confirmed_by=:actor,confirmed_at=clock_timestamp(),revision=revision+1
				WHERE dog_id=:dog AND revision=:revision
				""").param("dog",dog).param("revision",revision).param("actor",actor).update()!=1) throw BehaviorException.stale();
	}
	private static Instant time(ResultSet rs,String key) throws SQLException {
		var value=rs.getObject(key,OffsetDateTime.class);return value==null?null:value.toInstant();
	}
}
