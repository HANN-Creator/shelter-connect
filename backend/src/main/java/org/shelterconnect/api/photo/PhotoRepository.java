package org.shelterconnect.api.photo;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import static org.shelterconnect.api.photo.PhotoTypes.*;

@Repository
public class PhotoRepository {
	private static final RowMapper<Stored> PHOTO = (rs, n) -> new Stored(rs.getObject("id", UUID.class), rs.getObject("dog_id", UUID.class),
			rs.getString("storage_bucket"), rs.getString("storage_key"), rs.getInt("sort_order"), rs.getString("caption"), rs.getObject("updated_at", OffsetDateTime.class).toInstant());
	private final JdbcClient jdbc;
	public PhotoRepository(JdbcClient jdbc) { this.jdbc = jdbc; }

	boolean hasConversation(UUID user, UUID dog) {
		return jdbc.sql("""
				SELECT EXISTS (SELECT 1 FROM shelter.chat_sessions c
				JOIN shelter.chat_messages a ON a.session_id=c.id AND a.dog_id=c.dog_id
				JOIN shelter.chat_messages q ON q.id=a.reply_to_message_id AND q.session_id=c.id
				WHERE c.user_id=:user AND c.dog_id=:dog AND a.role='ASSISTANT' AND a.processing_status='COMPLETED'
				AND q.role='USER' AND q.processing_status='COMPLETED')
				""").param("user", user).param("dog", dog).query(Boolean.class).single();
	}
	List<Stored> photos(UUID dog, PhotoQuery.Cursor after, int count) {
		var query = jdbc.sql("SELECT * FROM shelter.dog_photos WHERE dog_id=:dog AND rights_status='GRANTED'"
				+ (after == null ? "" : " AND (sort_order,id)>(:order,:id)") + " ORDER BY sort_order,id LIMIT :count FOR SHARE")
				.param("dog", dog).param("count", count);
		if (after != null) query = query.param("order", after.order()).param("id", after.id());
		return query.query(PHOTO).list();
	}
	Optional<Stored> current(UUID dog, UUID id) {
		return jdbc.sql("SELECT * FROM shelter.dog_photos WHERE dog_id=:dog AND id=:id AND rights_status='GRANTED' FOR SHARE")
				.param("dog", dog).param("id", id).query(PHOTO).optional();
	}
}
