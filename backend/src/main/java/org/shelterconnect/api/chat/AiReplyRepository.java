package org.shelterconnect.api.chat;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;
import static org.shelterconnect.api.chat.AiTypes.*;
import static org.shelterconnect.api.chat.ChatResponses.*;

@Repository
public class AiReplyRepository {
	private static final RowMapper<Observation> OBS=(rs,n)->new Observation(rs.getObject("id",UUID.class),rs.getString("category"),rs.getString("content"),time(rs,"observed_at"),time(rs,"updated_at"));
	private final JdbcClient jdbc;
	private final JsonMapper json;
	public AiReplyRepository(JdbcClient jdbc,JsonMapper json) { this.jdbc=jdbc;this.json=json; }
	Optional<Request> request(UUID session,UUID id) {
		return jdbc.sql("SELECT *,generation_expires_at>clock_timestamp() AS active FROM shelter.chat_messages WHERE session_id=:session AND id=:id AND role='USER' FOR UPDATE")
				.param("session",session).param("id",id).query((rs,n)->new Request(ChatRepository.MESSAGE.mapRow(rs,n),rs.getObject("generation_token",UUID.class),time(rs,"generation_expires_at"),rs.getInt("generation_attempts"),rs.getBoolean("active"))).optional();
	}
	Optional<Message> reply(UUID request) {
		return jdbc.sql("SELECT * FROM shelter.chat_messages WHERE reply_to_message_id=:id")
				.param("id",request).query(ChatRepository.MESSAGE).optional();
	}
	void expire(UUID session) {
		jdbc.sql("UPDATE shelter.chat_messages SET processing_status='FAILED',failure_code='GENERATION_EXPIRED',generation_token=NULL,generation_expires_at=NULL"
				+" WHERE session_id=:session AND generation_token IS NOT NULL AND generation_expires_at<=clock_timestamp()")
				.param("session",session).update();
	}
	boolean otherActive(UUID session,UUID request) {
		return jdbc.sql("SELECT EXISTS (SELECT 1 FROM shelter.chat_messages WHERE session_id=:session AND id<>:id AND generation_token IS NOT NULL AND generation_expires_at>clock_timestamp())")
				.param("session",session).param("id",request).query(Boolean.class).single();
	}
	void claim(UUID request,UUID token,AiProperties properties) {
		jdbc.sql("UPDATE shelter.chat_messages SET processing_status='PENDING',failure_code=NULL,generation_token=:token,"
				+" generation_expires_at=clock_timestamp()+(:seconds*interval '1 second'),generation_attempts=generation_attempts+1,generation_model=:model,generation_response_id=NULL WHERE id=:id")
				.param("token",token).param("seconds",properties.leaseSeconds()).param("model",properties.model()).param("id",request).update();
	}
	Context context(Session session,Message message) {
		String name=jdbc.sql("SELECT name FROM shelter.dogs WHERE id=:id").param("id",session.dogId()).query(String.class).single();
		var candidates=jdbc.sql("SELECT id,category,content,observed_at,updated_at FROM shelter.dog_observations WHERE dog_id=:dog AND status='CONFIRMED' ORDER BY observed_at DESC,id DESC LIMIT 32")
				.param("dog",session.dogId()).query(OBS).list();
		var observations=new ArrayList<Observation>();int budget=16000;
		for(var observation:candidates) {
			if(observation.content().length()>budget) continue;
			observations.add(observation);budget-=observation.content().length();
			if(observations.size()==16) break;
		}
		var recent=jdbc.sql("SELECT content FROM shelter.chat_messages WHERE session_id=:session AND role='USER' AND (created_at,id)<(:at,:id) ORDER BY created_at DESC,id DESC LIMIT 6")
				.param("session",session.id()).param("at",message.createdAt().atOffset(ZoneOffset.UTC)).param("id",message.id()).query(String.class).list();
		var history=new ArrayList<String>();budget=6000;
		for(var content:recent) { if(content.length()>budget) break;history.add(content);budget-=content.length(); }
		Collections.reverse(history);
		return new Context(name,message.text(),List.copyOf(history),List.copyOf(observations));
	}
	boolean stillConfirmed(UUID dog,List<Observation> used) {
		for(var observation:used.stream().sorted(Comparator.comparing(o->o.id().toString())).toList()) {
			var current=jdbc.sql("SELECT id,category,content,observed_at,updated_at FROM shelter.dog_observations WHERE dog_id=:dog AND id=:id AND status='CONFIRMED' FOR SHARE")
					.param("dog",dog).param("id",observation.id()).query(OBS).optional();
			if(current.isEmpty() || !current.get().equals(observation)) return false;
		}
		return true;
	}
	Message complete(Work work,Generated generated,List<Observation> used) {
		Message reply=jdbc.sql("""
				INSERT INTO shelter.chat_messages(session_id,dog_id,role,content,reply_to_message_id,processing_status,needs_shelter_confirmation,created_at,updated_at)
				VALUES (:session,:dog,'ASSISTANT',:text,:request,'COMPLETED',:confirmation,
				GREATEST(clock_timestamp(),(SELECT max(created_at)+interval '1 microsecond' FROM shelter.chat_messages WHERE session_id=:session)),clock_timestamp()) RETURNING *
				""").param("session",work.sessionId()).param("dog",work.dogId()).param("text",generated.text()).param("request",work.requestId())
				.param("confirmation",generated.needsShelterConfirmation()).query(ChatRepository.MESSAGE).single();
		for(var observation:used) {
			jdbc.sql("INSERT INTO shelter.chat_message_observations(message_id,observation_id,dog_id,observation_snapshot) VALUES (:message,:observation,:dog,:snapshot)")
					.param("message",reply.id()).param("observation",observation.id()).param("dog",work.dogId()).param("snapshot",json.writeValueAsString(observation)).update();
		}
		int changed=jdbc.sql("UPDATE shelter.chat_messages SET processing_status='COMPLETED',failure_code=NULL,generation_token=NULL,generation_expires_at=NULL,generation_response_id=:response WHERE id=:id AND generation_token=:token")
				.param("response",generated.responseId()).param("id",work.requestId()).param("token",work.token()).update();
		if(changed!=1) throw new IllegalStateException("Generation ownership changed");
		jdbc.sql("UPDATE shelter.chat_sessions SET updated_at=clock_timestamp() WHERE id=:id").param("id",work.sessionId()).update();
		return reply;
	}
	void fail(Work work,String code) {
		jdbc.sql("UPDATE shelter.chat_messages SET processing_status='FAILED',failure_code=:code,generation_token=NULL,generation_expires_at=NULL WHERE id=:id AND generation_token=:token AND processing_status='PENDING'")
				.param("code",code).param("id",work.requestId()).param("token",work.token()).update();
	}
	private static Instant time(ResultSet rs,String field) throws SQLException { var date=rs.getObject(field,OffsetDateTime.class);return date==null?null:date.toInstant(); }
}
