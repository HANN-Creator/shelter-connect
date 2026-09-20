package org.shelterconnect.api;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shelterconnect.api.auth.JwtTestConfiguration;
import org.shelterconnect.api.auth.JwtTestSupport;
import org.shelterconnect.api.chat.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.shelterconnect.api.chat.AiTypes.*;

@Tag("postgres") @SpringBootTest(properties={"app.ai.enabled=true","app.ai.api-key=test-only"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(JwtTestConfiguration.class)
class AiReplyPostgresTest {
	@Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired JsonMapper json; @Autowired JwtTestSupport tokens;
	@MockitoBean AiProvider provider; @MockitoSpyBean AiProperties properties;
	private UUID user,other,subject,otherSubject,shelter,dog,otherDog,session,message,observation;
	private String token,otherToken;
	private ExecutorService executor;
	@BeforeAll static void migrate() throws Exception { SchemaMigrationTest.migratePostgres(); }
	@BeforeEach void fixtures() throws Exception {
		user=UUID.randomUUID();other=UUID.randomUUID();subject=UUID.randomUUID();otherSubject=UUID.randomUUID();shelter=UUID.randomUUID();dog=UUID.randomUUID();otherDog=UUID.randomUUID();observation=UUID.randomUUID();
		executor=Executors.newFixedThreadPool(2);
		jdbc.update("INSERT INTO shelter.app_users(id,display_name,auth_provider,auth_subject) VALUES (?,'대화 테스트',?,?),(?,'다른 사용자',?,?)",user,tokens.properties.providerKey(),subject.toString(),other,tokens.properties.providerKey(),otherSubject.toString());
		jdbc.update("UPDATE shelter.app_users SET role='OPERATOR' WHERE id=?",other);
		jdbc.update("INSERT INTO shelter.shelters(id,name,region,is_public,approval_status,reviewed_by,reviewed_at) VALUES (?,'가상 보호소','가상',true,'APPROVED',?,now())",shelter,user);
		for(UUID id:List.of(dog,otherDog)) jdbc.update("INSERT INTO shelter.dogs(id,shelter_id,name,avatar_key,is_public,adoption_status) VALUES (?,?,'봄이','bomi',true,'AVAILABLE')",id,shelter);
		observation(observation,dog,"CONFIRMED","공을 천천히 따라가요.");
		observation(UUID.randomUUID(),dog,"DRAFT","절대로 물지 않는다는 미확인 내용");
		observation(UUID.randomUUID(),dog,"RETRACTED","철회된 내용");
		observation(UUID.randomUUID(),otherDog,"CONFIRMED","다른 강아지만 수영을 좋아해요.");
		token=tokens.token(subject);otherToken=tokens.token(otherSubject);
		session=UUID.fromString(body(auth(post("/v1/dogs/"+dog+"/chat-sessions"),token),201).at("/data/id").asText());
		message=addMessage("q1","무슨 놀이를 좋아해?");
		doAnswer(call->{
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			Context context=call.getArgument(0);
			return new Generated("난 공을 천천히 따라가는 걸 좋아해!",false,List.of(context.observations().getFirst().id()),"resp_test");
		}).when(provider).generate(any());
	}
	@AfterEach void cleanup() {
		if(executor!=null) executor.close();
		jdbc.execute("ALTER TABLE shelter.chat_message_observations DROP CONSTRAINT IF EXISTS ai_reply_test_reject");
		jdbc.update("DELETE FROM shelter.chat_message_observations WHERE dog_id IN (?,?)",dog,otherDog);
		jdbc.update("DELETE FROM shelter.chat_messages WHERE dog_id IN (?,?)",dog,otherDog);
		jdbc.update("DELETE FROM shelter.chat_sessions WHERE dog_id IN (?,?)",dog,otherDog);
		jdbc.update("DELETE FROM shelter.dog_observations WHERE dog_id IN (?,?)",dog,otherDog);
		jdbc.update("DELETE FROM shelter.dogs WHERE id IN (?,?)",dog,otherDog);
		jdbc.update("DELETE FROM shelter.shelters WHERE id=?",shelter);
		jdbc.update("DELETE FROM shelter.app_users WHERE id IN (?,?)",user,other);
	}
	@Test void confirmedOwnDogFactsAreStoredWithSnapshotAndCompletedRequest() throws Exception {
		var result=reply(message,false,201).get("data");
		assertThat(result.get("processingStatus").asText()).isEqualTo("COMPLETED");
		assertThat(result.at("/reply/role").asText()).isEqualTo("ASSISTANT");
		assertThat(result.at("/reply/replyToMessageId").asText()).isEqualTo(message.toString());
		assertThat(result.at("/reply/needsShelterConfirmation").asBoolean()).isFalse();
		assertThat(reply(message,false,200).get("data")).isEqualTo(result);
		var capture=org.mockito.ArgumentCaptor.forClass(Context.class);verify(provider,times(1)).generate(capture.capture());
		assertThat(capture.getValue().observations()).hasSize(1);assertThat(capture.getValue().observations().getFirst().id()).isEqualTo(observation);
		assertThat(capture.getValue().toString()).doesNotContain("다른 강아지","미확인 내용","철회된 내용");
		String snapshot=jdbc.queryForObject("SELECT observation_snapshot FROM shelter.chat_message_observations WHERE dog_id=?",String.class,dog);
		assertThat(json.readTree(snapshot).get("content").asText()).isEqualTo("공을 천천히 따라가요.");
		assertThat(jdbc.queryForObject("SELECT processing_status FROM shelter.chat_messages WHERE id=?",String.class,message)).isEqualTo("COMPLETED");
		assertThat(jdbc.queryForObject("SELECT generation_response_id FROM shelter.chat_messages WHERE id=?",String.class,message)).isEqualTo("resp_test");
		doReturn(false).when(properties).enabled();
		assertThat(reply(message,false,200).get("data")).isEqualTo(result);
	}
	@Test void missingRecordsOrUnknownCitationsProduceConfirmationInsteadOfInventedFacts() throws Exception {
		doReturn(new Generated("입질이 절대 없어!",false,List.of(UUID.randomUUID()),"resp_bad")).when(provider).generate(any());
		var result=reply(message,false,201).get("data");
		assertThat(result.at("/reply/needsShelterConfirmation").asBoolean()).isTrue();
		assertThat(result.at("/reply/text").asText()).isEqualTo(AiTypes.unknown().text());
		assertThat(count("chat_message_observations")).isZero();
		jdbc.update("UPDATE shelter.dog_observations SET status='RETRACTED' WHERE dog_id=?",dog);
		UUID second=addMessage("q2","건강은 어때?");reply(second,false,201);
		verify(provider,times(1)).generate(any());
	}
	@Test void greetingIsFriendlyWithoutAModelCall() throws Exception {
		UUID greeting=addMessage("greeting","안녕!");
		var result=reply(greeting,false,201).at("/data/reply");
		assertThat(result.get("needsShelterConfirmation").asBoolean()).isFalse();
		verifyNoInteractions(provider);
	}
	@Test void failuresRequireExplicitRetryAndAttemptsAreBounded() throws Exception {
		doThrow(new AiFailure("AI_RATE_LIMITED")).when(provider).generate(any());
		var failed=reply(message,false,200).get("data");
		assertThat(failed.get("failureCode").asText()).isEqualTo("AI_RATE_LIMITED");assertThat(failed.get("retryable").asBoolean()).isTrue();
		reply(message,false,200);verify(provider,times(1)).generate(any());
		reply(message,true,200);reply(message,true,200);
		assertThat(reply(message,true,200).at("/data/retryable").asBoolean()).isFalse();verify(provider,times(3)).generate(any());
		assertThat(count("chat_messages")).isEqualTo(1);assertThat(count("chat_message_observations")).isZero();
	}
	@Test void successfulExplicitRetryUsesOriginalMessageAndCreatesOnlyOneAnswer() throws Exception {
		doThrow(new AiFailure("AI_TIMEOUT")).doReturn(new Generated("난 공을 따라가!",false,List.of(observation),"resp_retry")).when(provider).generate(any());
		reply(message,false,200);reply(message,true,201);reply(message,true,200);
		assertThat(count("chat_messages")).isEqualTo(2);verify(provider,times(2)).generate(any());
	}
	@Test void allOwnershipAndConfigurationChecksPrecedeProviderCalls() throws Exception {
		mvc.perform(auth(post(path(message)),otherToken)).andExpect(status().isNotFound());
		mvc.perform(post(path(message)).header("Authorization","Bearer "+tokens.token(UUID.randomUUID()))).andExpect(status().isForbidden());
		mvc.perform(auth(post(path(UUID.randomUUID())),token)).andExpect(status().isNotFound());
		UUID anotherSession=UUID.fromString(body(auth(post("/v1/dogs/"+otherDog+"/chat-sessions"),token),201).at("/data/id").asText());
		mvc.perform(auth(post("/v1/chat-sessions/"+anotherSession+"/messages/"+message+"/reply"),token)).andExpect(status().isNotFound());
		doReturn(false).when(properties).enabled();reply(message,false,503);
		assertThat(jdbc.queryForObject("SELECT generation_attempts FROM shelter.chat_messages WHERE id=?",Integer.class,message)).isZero();
		verifyNoInteractions(provider);
	}
	@Test void providerCallsReleaseDbLocksAndConcurrentRetriesOnlyObservePending() throws Exception {
		var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
		doAnswer(call->{entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return new Generated("난 공을 따라가!",false,List.of(observation),"resp_first");}).when(provider).generate(any());
		Future<JsonNode> running=executor.submit(()->reply(message,false,201));
		try {
			assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
			assertThat(reply(message,false,202).at("/data/processingStatus").asText()).isEqualTo("PENDING");
			UUID another=addMessage("q2","산책은?");
			reply(another,false,409);
			verify(provider,times(1)).generate(any());
		} finally {release.countDown();}
		assertThat(running.get(10,TimeUnit.SECONDS).at("/data/reply/id").asText()).isNotBlank();
	}
	@Test void expiredLeaseRequiresRetryAndOldWorkerCannotOverwriteNewAnswer() throws Exception {
		var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var attempts=new AtomicInteger();
		doAnswer(call->{
			int n=attempts.incrementAndGet();if(n==1){entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();}
			return new Generated(n==1?"오래된 응답":"새 시도의 응답",false,List.of(observation),"resp_"+n);
		}).when(provider).generate(any());
		Future<JsonNode> first=executor.submit(()->reply(message,false,200));
		try {
			assertThat(entered.await(5,TimeUnit.SECONDS)).isTrue();
			jdbc.update("UPDATE shelter.chat_messages SET generation_expires_at=clock_timestamp()-interval '1 second' WHERE id=?",message);
			assertThat(reply(message,false,200).at("/data/failureCode").asText()).isEqualTo("GENERATION_EXPIRED");
			assertThat(reply(message,true,201).at("/data/reply/text").asText()).isEqualTo("새 시도의 응답");
		} finally {release.countDown();}
		assertThat(first.get(10,TimeUnit.SECONDS).at("/data/reply/text").asText()).isEqualTo("새 시도의 응답");
		assertThat(count("chat_messages")).isEqualTo(2);assertThat(attempts.get()).isEqualTo(2);
	}
	@ParameterizedTest @ValueSource(strings={"dog","shelter","session","account"})
	void contextIsCheckedAgainBeforePublishing(String changed) throws Exception {
		doAnswer(call->{
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			switch(changed){
				case "dog" -> jdbc.update("UPDATE shelter.dogs SET is_public=false WHERE id=?",dog);
				case "shelter" -> jdbc.update("UPDATE shelter.shelters SET is_public=false WHERE id=?",shelter);
				case "session" -> jdbc.update("UPDATE shelter.chat_sessions SET status='CLOSED' WHERE id=?",session);
				case "account" -> jdbc.update("UPDATE shelter.app_users SET disabled_at=now() WHERE id=?",user);
			}
			return new Generated("공을 따라가!",false,List.of(observation),"resp_old");
		}).when(provider).generate(any());
		reply(message,false,changed.equals("account")?403:200);
		assertThat(count("chat_messages")).isEqualTo(1);assertThat(count("chat_message_observations")).isZero();
		assertThat(jdbc.queryForObject("SELECT processing_status FROM shelter.chat_messages WHERE id=?",String.class,message)).isEqualTo("FAILED");
	}
	@Test void retractedEvidenceCannotBePublishedOrCitedAfterGeneration() throws Exception {
		doAnswer(call->{jdbc.update("UPDATE shelter.dog_observations SET status='RETRACTED' WHERE id=?",observation);return new Generated("공을 따라가!",false,List.of(observation),"resp_stale");}).when(provider).generate(any());
		assertThat(reply(message,false,201).at("/data/reply/needsShelterConfirmation").asBoolean()).isTrue();assertThat(count("chat_message_observations")).isZero();
	}
	@Test void failedCitationInsertRollsBackAnswerAndRequestCompletionTogether() throws Exception {
		// A fixture-scoped constraint forces failure after the answer INSERT, inside completion's transaction.
		jdbc.execute("ALTER TABLE shelter.chat_message_observations ADD CONSTRAINT ai_reply_test_reject CHECK (observation_id <> '"+observation+"'::uuid) NOT VALID");
		reply(message,false,500);
		assertThat(count("chat_messages")).isEqualTo(1);assertThat(count("chat_message_observations")).isZero();
		assertThat(jdbc.queryForObject("SELECT processing_status FROM shelter.chat_messages WHERE id=?",String.class,message)).isEqualTo("FAILED");
		jdbc.execute("ALTER TABLE shelter.chat_message_observations DROP CONSTRAINT ai_reply_test_reject");
		reply(message,true,201);assertThat(count("chat_messages")).isEqualTo(2);
	}
	private void observation(UUID id,UUID target,String status,String content) {
		jdbc.update("INSERT INTO shelter.dog_observations(id,dog_id,category,content,observed_at,recorded_by,status,confirmed_by,confirmed_at,source_note) VALUES (?,?,'PLAY',?,now(),?,?,?,now(),'internal-contact-not-sent')",id,target,content,user,status,user);
	}
	private int count(String table) {return jdbc.queryForObject("SELECT count(*) FROM shelter."+table+" WHERE dog_id=?",Integer.class,dog);}
	private UUID addMessage(String key,String text) throws Exception {
		return UUID.fromString(body(auth(post("/v1/chat-sessions/"+session+"/messages"),token).contentType("application/json").content(json.createObjectNode().put("clientMessageId",key).put("text",text).toString()),201).at("/data/id").asText());
	}
	private JsonNode reply(UUID id,boolean retry,int status) throws Exception {
		var req=auth(post(path(id)),token);if(retry) req=req.contentType("application/json").content("{\"retry\":true}");return body(req,status);
	}
	private String path(UUID id) { return "/v1/chat-sessions/"+session+"/messages/"+id+"/reply"; }
	private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req,String token) {return req.header("Authorization","Bearer "+token);}
	private JsonNode body(MockHttpServletRequestBuilder req,int code) throws Exception {
		MvcResult result=mvc.perform(req).andExpect(status().is(code)).andExpect(header().string("Cache-Control","no-store")).andReturn();return json.readTree(result.getResponse().getContentAsString());
	}
}
