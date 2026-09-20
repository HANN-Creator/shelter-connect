package org.shelterconnect.api.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import static org.shelterconnect.api.chat.ChatResponses.*;

@Repository
public class ChatRepository {
	private static final String AVAILABLE="s.approval_status='APPROVED' AND s.is_public AND d.is_public"
			+" AND d.archived_at IS NULL AND d.adoption_status IN ('AVAILABLE','IN_PROGRESS')";
	private static final String SESSIONS="SELECT c.*, (c.status='OPEN' AND "+AVAILABLE+") AS can_send"
			+" FROM shelter.chat_sessions c JOIN shelter.dogs d ON d.id=c.dog_id JOIN shelter.shelters s ON s.id=d.shelter_id";
	private static final RowMapper<Session> SESSION=(rs,n)->new Session(uuid(rs,"id"),uuid(rs,"dog_id"),rs.getString("status"),rs.getBoolean("can_send"),time(rs,"created_at"),time(rs,"updated_at"));
	private static final RowMapper<Message> MESSAGE=(rs,n)->new Message(uuid(rs,"id"),uuid(rs,"session_id"),uuid(rs,"dog_id"),rs.getString("role"),rs.getString("content"),rs.getString("client_message_id"),uuid(rs,"reply_to_message_id"),rs.getString("processing_status"),rs.getString("failure_code"),rs.getBoolean("needs_shelter_confirmation"),time(rs,"created_at"),time(rs,"updated_at"));
	private final JdbcClient jdbc;
	public ChatRepository(JdbcClient jdbc) { this.jdbc=jdbc; }

	public boolean lockAvailableDog(UUID dog) {
		return jdbc.sql("SELECT d.id FROM shelter.dogs d JOIN shelter.shelters s ON s.id=d.shelter_id WHERE d.id=:dog AND "+AVAILABLE+" FOR SHARE OF s,d")
				.param("dog",dog).query(UUID.class).optional().isPresent();
	}
	public Optional<Session> session(UUID user,UUID id,boolean lock) {
		return jdbc.sql(SESSIONS+" WHERE c.user_id=:user AND c.id=:id"+(lock?" FOR UPDATE OF c":""))
				.param("user",user).param("id",id).query(SESSION).optional();
	}
	public Optional<Session> openSession(UUID user,UUID dog) {
		return jdbc.sql(SESSIONS+" WHERE c.user_id=:user AND c.dog_id=:dog AND c.status='OPEN' ORDER BY c.created_at DESC,c.id DESC LIMIT 1")
				.param("user",user).param("dog",dog).query(SESSION).optional();
	}
	public Session createSession(UUID user,UUID dog) {
		UUID id=jdbc.sql("INSERT INTO shelter.chat_sessions(user_id,dog_id,created_at,updated_at) VALUES (:user,:dog,clock_timestamp(),clock_timestamp()) RETURNING id")
				.param("user",user).param("dog",dog).query(UUID.class).single();
		return session(user,id,false).orElseThrow();
	}
	public List<Session> sessions(UUID user,UUID dog,ChatInput.Cursor after,int count) {
		var query=jdbc.sql(SESSIONS+" WHERE c.user_id=:user"+(dog==null?"":" AND c.dog_id=:dog")
				+(after==null?"":" AND (c.created_at,c.id)<(:at,:last)")+" ORDER BY c.created_at DESC,c.id DESC LIMIT :count")
				.param("user",user).param("count",count);
		if(dog!=null) query=query.param("dog",dog);
		if(after!=null) query=query.param("at",utc(after.at())).param("last",after.id());
		return query.query(SESSION).list();
	}
	public Optional<Message> messageByKey(UUID session,String key) {
		return jdbc.sql("SELECT * FROM shelter.chat_messages WHERE session_id=:session AND client_message_id=:key")
				.param("session",session).param("key",key).query(MESSAGE).optional();
	}
	public Message createMessage(Session session,ChatInput.Send input) {
		// The session lock serializes appends. Stamp after waiting, not at transaction start.
		Message message=jdbc.sql("""
				INSERT INTO shelter.chat_messages(session_id,dog_id,role,content,client_message_id,processing_status,created_at,updated_at)
				VALUES (:session,:dog,'USER',:text,:key,'PENDING',
				GREATEST(clock_timestamp(),(SELECT max(created_at)+interval '1 microsecond' FROM shelter.chat_messages WHERE session_id=:session)),clock_timestamp())
				RETURNING *
				""").param("session",session.id()).param("dog",session.dogId()).param("text",input.text()).param("key",input.clientMessageId()).query(MESSAGE).single();
		jdbc.sql("UPDATE shelter.chat_sessions SET updated_at=clock_timestamp() WHERE id=:id").param("id",session.id()).update();
		return message;
	}
	public List<Message> messages(UUID user,UUID session,ChatInput.Cursor after,int count) {
		var query=jdbc.sql("SELECT m.* FROM shelter.chat_messages m JOIN shelter.chat_sessions c ON c.id=m.session_id"
				+" WHERE c.user_id=:user AND c.id=:session"+(after==null?"":" AND (m.created_at,m.id)>(:at,:last)")
				+" ORDER BY m.created_at,m.id LIMIT :count").param("user",user).param("session",session).param("count",count);
		if(after!=null) query=query.param("at",utc(after.at())).param("last",after.id());
		return query.query(MESSAGE).list();
	}
	private static UUID uuid(ResultSet rs,String key) throws SQLException { return rs.getObject(key,UUID.class); }
	private static Instant time(ResultSet rs,String key) throws SQLException { return rs.getObject(key,OffsetDateTime.class).toInstant(); }
	private static OffsetDateTime utc(Instant time) { return time.atOffset(ZoneOffset.UTC); }
}
