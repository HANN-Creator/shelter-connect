package org.shelterconnect.api;

import java.nio.file.Path;
import java.util.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shelterconnect.api.auth.JwtTestConfiguration;
import org.shelterconnect.api.auth.JwtTestSupport;
import org.shelterconnect.sample.SampleDataLoader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@Import(JwtTestConfiguration.class) @Transactional
class ChatPostgresTest {
	private static final UUID USER=id("020",4),OTHER=id("020",1),DOG=id("022",1),DOG2=id("022",2),PRIVATE=id("022",5),SHELTER=id("021",1);
	private static final UUID SUBJECT=UUID.randomUUID(),OTHER_SUB=UUID.randomUUID();
	@Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired DataSource dataSource;
	@Autowired JsonMapper json; @Autowired JwtTestSupport tokens;
	@BeforeAll static void migrate() throws Exception { SchemaMigrationTest.migratePostgres(); }
	@BeforeEach void fixtures() throws Exception {
		var connection=DataSourceUtils.getConnection(dataSource);
		try { SampleDataLoader.insert(connection,SampleDataLoader.read(Path.of("sample-data/dataset.json"))); }
		finally { DataSourceUtils.releaseConnection(connection,dataSource); }
		link(USER,SUBJECT); link(OTHER,OTHER_SUB);
	}
	@Test void sameUserAndDogResumeOpenSessionAndOtherDogsAndUsersAreSeparate() throws Exception {
		var session=open(DOG,SUBJECT,201);
		assertThat(open(DOG,SUBJECT,200)).isEqualTo(session);
		assertThat(session.get("canSend").asBoolean()).isTrue();
		assertThat(open(DOG2,SUBJECT,201).get("id")).isNotEqualTo(session.get("id"));
		assertThat(open(DOG,OTHER_SUB,201).get("id")).isNotEqualTo(session.get("id"));
		assertThat(body(auth(get("/v1/chat-sessions/"+session.get("id").asText()),SUBJECT),200).get("data")).isEqualTo(session);
		assertThat(body(auth(get("/v1/me/chat-sessions"),SUBJECT),200).get("data").size()).isEqualTo(2);
		mvc.perform(auth(post("/v1/dogs/"+PRIVATE+"/chat-sessions"),SUBJECT)).andExpect(status().isNotFound());
	}
	@Test void emptyMessagesStayEmptyAndContentAndPendingStateSurviveRetry() throws Exception {
		String session=open(DOG,SUBJECT,201).get("id").asText();
		assertThat(body(auth(get(messages(session)),SUBJECT),200).get("data").size()).isZero();
		String text=" 안녕!\n무슨 놀이를 좋아해? 🐶 ";
		var saved=send(session,"request-1",text,201);
		assertThat(saved.get("text").asText()).isEqualTo(text);
		assertThat(saved.get("role").asText()).isEqualTo("USER");
		assertThat(saved.get("processingStatus").asText()).isEqualTo("PENDING");
		assertThat(saved.get("dogId").asText()).isEqualTo(DOG.toString());
		assertThat(saved.get("replyToMessageId").isNull()).isTrue();
		assertThat(send(session,"request-1",text,200)).isEqualTo(saved);
		mvc.perform(payload(post(messages(session)),SUBJECT,"request-1","다른 내용")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("MESSAGE_ID_CONFLICT"));
		var history=body(auth(get(messages(session)),SUBJECT),200);
		assertThat(history.get("data").size()).isEqualTo(1);
		assertThat(history.at("/data/0")).isEqualTo(saved);
		assertThat(history.get("nextCursor").isNull()).isTrue();
	}
	@Test void assistantAndFailedStatesAreReadableButClientCannotForgeOrResetThem() throws Exception {
		String session=open(DOG,SUBJECT,201).get("id").asText();
		var question=send(session,"q1","안녕",201);
		UUID answer=UUID.randomUUID();
		jdbc.update("UPDATE shelter.chat_messages SET processing_status='COMPLETED' WHERE id=?",UUID.fromString(question.get("id").asText()));
		jdbc.update("INSERT INTO shelter.chat_messages(id,session_id,dog_id,role,content,reply_to_message_id,processing_status,needs_shelter_confirmation,created_at) VALUES (?,?,?,'ASSISTANT','보호소에 확인해 줘',?,'COMPLETED',true,clock_timestamp())",answer,UUID.fromString(session),DOG,UUID.fromString(question.get("id").asText()));
		var failed=send(session,"q2","질문",201);
		jdbc.update("UPDATE shelter.chat_messages SET processing_status='FAILED',failure_code='PROVIDER_UNAVAILABLE' WHERE id=?",UUID.fromString(failed.get("id").asText()));
		assertThat(send(session,"q2","질문",200).get("processingStatus").asText()).isEqualTo("FAILED");
		var history=body(auth(get(messages(session)),SUBJECT),200).get("data");
		assertThat(history.size()).isEqualTo(3);
		assertThat(history.get(1).get("role").asText()).isEqualTo("ASSISTANT");
		assertThat(history.get(1).get("needsShelterConfirmation").asBoolean()).isTrue();
		for(String forbidden:List.of("role","dogId","userId","processingStatus","replyToMessageId","needsShelterConfirmation")) {
			var input=json.createObjectNode().put("clientMessageId","forged").put("text","가짜 답변").put(forbidden,"forged");
			mvc.perform(auth(post(messages(session)),SUBJECT).contentType("application/json").content(input.toString())).andExpect(status().isBadRequest());
		}
		assertThat(body(auth(get(messages(session)),SUBJECT),200).get("data").size()).isEqualTo(3);
	}
	@Test void evenOperatorCannotReadOrWriteSomeoneElsesSession() throws Exception {
		String session=open(DOG,SUBJECT,201).get("id").asText();
		send(session,"q1","내 대화",201);
		for(String id:List.of(session,UUID.randomUUID().toString())) {
			mvc.perform(auth(get("/v1/chat-sessions/"+id),OTHER_SUB)).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CHAT_NOT_FOUND"));
			mvc.perform(auth(get(messages(id)),OTHER_SUB)).andExpect(status().isNotFound());
			mvc.perform(payload(post(messages(id)),OTHER_SUB,"q1","침범")).andExpect(status().isNotFound());
		}
		assertThat(body(auth(get("/v1/me/chat-sessions"),OTHER_SUB),200).get("data").size()).isZero();
	}
	@ParameterizedTest @ValueSource(strings={"dog-private","shelter-private","shelter-suspended","ADOPTED","PAUSED","archived"})
	void historyAndAcknowledgementRemainReadableButNewMessagesAndSessionsStop(String state) throws Exception {
		String session=open(DOG,SUBJECT,201).get("id").asText();
		var saved=send(session,"q1","안녕",201);
		switch(state) {
			case "dog-private" -> jdbc.update("UPDATE shelter.dogs SET is_public=false WHERE id=?",DOG);
			case "shelter-private" -> jdbc.update("UPDATE shelter.shelters SET is_public=false WHERE id=?",SHELTER);
			case "shelter-suspended" -> jdbc.update("UPDATE shelter.shelters SET is_public=false,approval_status='SUSPENDED' WHERE id=?",SHELTER);
			case "archived" -> jdbc.update("UPDATE shelter.dogs SET archived_at=now() WHERE id=?",DOG);
			default -> jdbc.update("UPDATE shelter.dogs SET adoption_status=? WHERE id=?",state,DOG);
		}
		assertThat(body(auth(get("/v1/chat-sessions/"+session),SUBJECT),200).at("/data/canSend").asBoolean()).isFalse();
		assertThat(body(auth(get(messages(session)),SUBJECT),200).get("data").size()).isEqualTo(1);
		assertThat(send(session,"q1","안녕",200)).isEqualTo(saved);
		mvc.perform(payload(post(messages(session)),SUBJECT,"q2","새 질문")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("DOG_UNAVAILABLE"));
		mvc.perform(auth(post("/v1/dogs/"+DOG+"/chat-sessions"),SUBJECT)).andExpect(status().isNotFound());
	}
	@Test void closedSessionRetainsHistoryAndOpeningDogCreatesAnotherSession() throws Exception {
		String session=open(DOG,SUBJECT,201).get("id").asText();
		send(session,"q1","안녕",201);
		jdbc.update("UPDATE shelter.chat_sessions SET status='CLOSED' WHERE id=?",UUID.fromString(session));
		assertThat(send(session,"q1","안녕",200).get("id").isString()).isTrue();
		mvc.perform(payload(post(messages(session)),SUBJECT,"q2","안녕")).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("SESSION_CLOSED"));
		assertThat(open(DOG,SUBJECT,201).get("id").asText()).isNotEqualTo(session);
	}
	@Test void chronologicalPagesHandleTiedTimestampsAndRejectOtherScopes() throws Exception {
		String session=open(DOG,SUBJECT,201).get("id").asText(),other=open(DOG2,SUBJECT,201).get("id").asText();
		for(int i=0;i<3;i++) send(session,"q"+i,"메시지 "+i,201);
		jdbc.update("UPDATE shelter.chat_messages SET created_at='2026-09-01T00:00:00Z' WHERE session_id=?",UUID.fromString(session));
		var first=body(auth(get(messages(session)).param("limit","1"),SUBJECT),200);
		String cursor=first.get("nextCursor").asText();
		var rest=body(auth(get(messages(session)).param("cursor",cursor),SUBJECT),200);
		Set<String> ids=new HashSet<>(); ids.add(first.at("/data/0/id").asText());
		for(JsonNode message:rest.get("data")) ids.add(message.get("id").asText());
		assertThat(ids).hasSize(3); assertThat(rest.get("nextCursor").isNull()).isTrue();
		mvc.perform(auth(get(messages(other)).param("cursor",cursor),SUBJECT)).andExpect(status().isBadRequest());
		mvc.perform(auth(get("/v1/me/chat-sessions").param("cursor",cursor),SUBJECT)).andExpect(status().isBadRequest());
	}
	@Test void sessionPagesUseStableCreationOrderAndDogFiltersAreScoped() throws Exception {
		String first=open(DOG,SUBJECT,201).get("id").asText();
		open(DOG2,SUBJECT,201);
		var page=body(auth(get("/v1/me/chat-sessions").param("limit","1"),SUBJECT),200);
		String cursor=page.get("nextCursor").asText();
		send(first,"late-message","오래된 대화방에 새 메시지",201);
		var rest=body(auth(get("/v1/me/chat-sessions").param("cursor",cursor),SUBJECT),200);
		assertThat(rest.get("data").size()).isEqualTo(1);
		assertThat(rest.at("/data/0/id").asText()).isEqualTo(first);
		var filtered=body(auth(get("/v1/me/chat-sessions").param("dogId",DOG.toString()),SUBJECT),200);
		assertThat(filtered.get("data").size()).isEqualTo(1);
		mvc.perform(auth(get("/v1/me/chat-sessions").param("dogId",DOG.toString()).param("cursor",cursor),SUBJECT)).andExpect(status().isBadRequest());
		mvc.perform(auth(get("/v1/me/chat-sessions").param("cursor",cursor),OTHER_SUB)).andExpect(status().isBadRequest());
	}
	@ParameterizedTest @ValueSource(strings={"unregistered","disabled"})
	void unavailableAccountsCannotReadOrAppend(String state) throws Exception {
		String session=open(DOG,SUBJECT,201).get("id").asText();
		UUID subject=SUBJECT;
		if(state.equals("disabled")) jdbc.update("UPDATE shelter.app_users SET disabled_at=now() WHERE id=?",USER);
		else subject=UUID.randomUUID();
		for(var request:List.of(get("/v1/me/chat-sessions"),get(messages(session)),get("/v1/chat-sessions/"+session),post("/v1/dogs/"+DOG+"/chat-sessions")))
			mvc.perform(auth(request,subject)).andExpect(status().isForbidden());
		mvc.perform(payload(post(messages(session)),subject,"q1","차단")).andExpect(status().isForbidden());
	}
	private JsonNode open(UUID dog,UUID subject,int status) throws Exception {
		var result=mvc.perform(auth(post("/v1/dogs/"+dog+"/chat-sessions"),subject)).andExpect(status().is(status)).andExpect(header().exists("Location")).andReturn();
		return json.readTree(result.getResponse().getContentAsString()).get("data");
	}
	private JsonNode send(String session,String key,String text,int code) throws Exception { return body(payload(post(messages(session)),SUBJECT,key,text),code).get("data"); }
	private MockHttpServletRequestBuilder payload(MockHttpServletRequestBuilder req,UUID subject,String key,String text) {
		return auth(req,subject).contentType("application/json").content(json.createObjectNode().put("clientMessageId",key).put("text",text).toString());
	}
	private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req,UUID subject) { return req.header("Authorization","Bearer "+tokens.token(subject)); }
	private JsonNode body(MockHttpServletRequestBuilder req,int code) throws Exception {
		return json.readTree(mvc.perform(req).andExpect(status().is(code)).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());
	}
	private void link(UUID user,UUID subject) { jdbc.update("UPDATE shelter.app_users SET auth_provider=?,auth_subject=? WHERE id=?",tokens.properties.providerKey(),subject.toString(),user); }
	private String messages(String session) { return "/v1/chat-sessions/"+session+"/messages"; }
	private static UUID id(String prefix,int suffix) { return UUID.fromString(prefix+"00000-0000-4000-8000-"+String.format("%012d",suffix)); }
}
