package org.shelterconnect.api;

import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shelterconnect.api.auth.JwtTestConfiguration;
import org.shelterconnect.api.auth.JwtTestSupport;
import org.shelterconnect.api.photo.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.shelterconnect.api.photo.PhotoTypes.*;

// Committed, isolated fixtures allow a fake Storage call to change rows between the two real transactions.
@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@Import(JwtTestConfiguration.class)
class PhotoPostgresTest {
	@Autowired JdbcTemplate jdbc;
	@Autowired MockMvc mvc;
	@Autowired JsonMapper json;
	@Autowired JwtTestSupport tokens;
	@MockitoBean PhotoStorage storage;
	private UUID user, other, subject, otherSubject, shelter, dog, otherDog, photo, session, request;
	@BeforeAll static void migrate() throws Exception { SchemaMigrationTest.migratePostgres(); }
	@BeforeEach void fixtures() {
		user=UUID.randomUUID(); other=UUID.randomUUID(); subject=UUID.randomUUID(); otherSubject=UUID.randomUUID();
		shelter=UUID.randomUUID(); dog=UUID.randomUUID(); otherDog=UUID.randomUUID();
		jdbc.update("INSERT INTO shelter.app_users(id,display_name,auth_provider,auth_subject) VALUES (?,'사진 테스트',?,?),(?,'다른 사용자',?,?)", user,tokens.properties.providerKey(),subject.toString(),other,tokens.properties.providerKey(),otherSubject.toString());
		jdbc.update("UPDATE shelter.app_users SET role='OPERATOR' WHERE id=?",other);
		jdbc.update("INSERT INTO shelter.shelters(id,name,region,is_public,approval_status,reviewed_by,reviewed_at) VALUES (?,'가상 보호소','가상',true,'APPROVED',?,now())",shelter,user);
		for(var id:List.of(dog,otherDog)) jdbc.update("INSERT INTO shelter.dogs(id,shelter_id,name,avatar_key,is_public,adoption_status) VALUES (?,?,'봄이','bomi',true,'AVAILABLE')",id,shelter);
		photo=photo(dog,9,"GRANTED","첫 사진");
		conversation(user,dog,true);
		doAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			return links(call.getArgument(0));
		}).when(storage).sign(anyList());
	}
	@AfterEach void cleanup() {
		jdbc.update("DELETE FROM shelter.dog_photos WHERE dog_id IN (?,?)",dog,otherDog);
		jdbc.update("DELETE FROM shelter.chat_messages WHERE dog_id IN (?,?)",dog,otherDog);
		jdbc.update("DELETE FROM shelter.chat_sessions WHERE dog_id IN (?,?)",dog,otherDog);
		jdbc.update("DELETE FROM shelter.dogs WHERE id IN (?,?)",dog,otherDog);
		jdbc.update("DELETE FROM shelter.shelters WHERE id=?",shelter);
		jdbc.update("DELETE FROM shelter.app_users WHERE id IN (?,?)",user,other);
	}
	@Test void orderedGrantedPhotosArePagedAndInternalFieldsStayPrivate() throws Exception {
		UUID first=photo(dog,2,"GRANTED",null), last=photo(dog,30,"GRANTED","마지막");
		photo(dog,0,"UNKNOWN","미확인"); photo(dog,1,"REVOKED","철회"); photo(otherDog,0,"GRANTED","다른 강아지");
		var page=read(subject,dog,null,"2",200);
		assertThat(page.at("/data/0/id").asText()).isEqualTo(first.toString());
		assertThat(page.at("/data/1/id").asText()).isEqualTo(photo.toString());
		assertThat(page.at("/data/0/caption").isNull()).isTrue();
		assertThat(page.at("/data/1/caption").asText()).isEqualTo("첫 사진");
		assertThat(page.at("/data/0/expiresAt").asText()).isNotBlank();
		assertThat(page.toString()).doesNotContain("storage_bucket","storageKey","sourceNote","rightsNote","internal-only","미확인","철회","다른 강아지");
		String cursor=page.get("nextCursor").asText();
		var second=read(subject,dog,cursor,"2",200);
		assertThat(second.get("data").size()).isEqualTo(1); assertThat(second.at("/data/0/id").asText()).isEqualTo(last.toString());
		assertThat(second.get("nextCursor").isNull()).isTrue();
		assertThat(read(subject,dog,cursor,"2",200).at("/data/0/id")).isEqualTo(second.at("/data/0/id"));
	}
	@Test void absentPhotosReturnEmptyWithoutStorageEvenWhenStorageIsNotConfigured() throws Exception {
		jdbc.update("UPDATE shelter.dog_photos SET rights_status='REVOKED' WHERE dog_id=?",dog);
		var page=read(subject,dog,null,null,200);
		assertThat(page.get("data").isEmpty()).isTrue(); assertThat(page.get("nextCursor").isNull()).isTrue();
		verifyNoInteractions(storage);
	}
	@Test void onlyTheCurrentUsersCompletedExchangeWithThisDogUnlocksPhotos() throws Exception {
		assertThat(read(otherSubject,dog,null,null,403).get("code").asText()).isEqualTo("PHOTO_LOCKED");
		assertThat(read(subject,otherDog,null,null,403).get("code").asText()).isEqualTo("PHOTO_LOCKED");
		jdbc.update("DELETE FROM shelter.chat_messages WHERE dog_id=? AND role='ASSISTANT'",dog);
		jdbc.update("UPDATE shelter.chat_messages SET processing_status='PENDING' WHERE id=?",request);
		assertThat(read(subject,dog,null,null,403).get("code").asText()).isEqualTo("PHOTO_LOCKED");
		jdbc.update("UPDATE shelter.chat_messages SET processing_status='FAILED',failure_code='AI_TIMEOUT' WHERE id=?",request);
		read(subject,dog,null,null,403);
		verifyNoInteractions(storage);
		jdbc.update("UPDATE shelter.chat_messages SET processing_status='COMPLETED',failure_code=NULL WHERE id=?",request);
		answer(session,request,dog);
		// A closed room's completed exchange still counts; an unverified/empty room alone does not.
		jdbc.update("UPDATE shelter.chat_sessions SET status='CLOSED' WHERE id=?",session);
		read(subject,dog,null,null,200);
	}
	@Test void cursorsAreBoundToUserAndDogAndMalformedInputsFail() throws Exception {
		photo(dog,20,"GRANTED",null);
		String cursor=read(subject,dog,null,"1",200).get("nextCursor").asText();
		clearInvocations(storage);
		conversation(other,dog,true); conversation(user,otherDog,true);
		read(otherSubject,dog,cursor,"1",400); read(subject,otherDog,cursor,"1",400);
		for(String invalid:List.of("", "a", "***", "x".repeat(513), Base64.getUrlEncoder().withoutPadding().encodeToString(("1\nphotos\n"+user+"\n"+dog+"\n2147483648\n"+photo).getBytes(java.nio.charset.StandardCharsets.UTF_8))))
			read(subject,dog,invalid,null,400);
		verifyNoInteractions(storage);
	}
	@Test void missingInactiveAndUnregisteredUsersCannotSignPhotos() throws Exception {
		read(subject,UUID.randomUUID(),null,null,404);
		read(UUID.randomUUID(),dog,null,null,403);
		jdbc.update("UPDATE shelter.app_users SET disabled_at=now() WHERE id=?",user);
		assertThat(read(subject,dog,null,null,403).get("code").asText()).isEqualTo("ACCOUNT_DISABLED");
		verifyNoInteractions(storage);
	}
	@ParameterizedTest @ValueSource(strings={"dog-private","shelter-private","shelter-suspended","archived","ADOPTED","PAUSED"})
	void visibilityIsRequiredBeforeAnyStorageCall(String state) throws Exception {
		changeVisibility(state); read(subject,dog,null,null,404); verifyNoInteractions(storage);
	}
	@ParameterizedTest @ValueSource(strings={"dog-private","shelter-private","shelter-suspended","archived","ADOPTED","PAUSED","rights","key","caption","order","account","removed","conversation"})
	void changesDuringSigningNeverReturnUrls(String state) throws Exception {
		doAnswer(call -> {
			assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
			switch(state) {
				case "rights" -> jdbc.update("UPDATE shelter.dog_photos SET rights_status='REVOKED' WHERE id=?",photo);
				case "key" -> jdbc.update("UPDATE shelter.dog_photos SET storage_key=? WHERE id=?",dog+"/replacement.jpg",photo);
				case "caption" -> jdbc.update("UPDATE shelter.dog_photos SET caption='바뀜' WHERE id=?",photo);
				case "order" -> jdbc.update("UPDATE shelter.dog_photos SET sort_order=99 WHERE id=?",photo);
				case "account" -> jdbc.update("UPDATE shelter.app_users SET disabled_at=now() WHERE id=?",user);
				case "removed" -> jdbc.update("DELETE FROM shelter.dog_photos WHERE id=?",photo);
				case "conversation" -> jdbc.update("DELETE FROM shelter.chat_messages WHERE dog_id=? AND role='ASSISTANT'",dog);
				default -> changeVisibility(state);
			}
			return links(call.getArgument(0));
		}).when(storage).sign(anyList());
		int expected=Set.of("rights","key","caption","order","removed").contains(state)?409:Set.of("account","conversation").contains(state)?403:404;
		var response=read(subject,dog,null,null,expected);
		assertThat(response.toString()).doesNotContain("signed.example", "url", "token");
	}
	@Test void storageFailureIsAnErrorNotAnEmptyGalleryAndCanBeRetried() throws Exception {
		doThrow(new PhotoException(502,"PHOTO_STORAGE_UNAVAILABLE","사진을 불러오지 못했어요.")).when(storage).sign(anyList());
		assertThat(read(subject,dog,null,null,502).get("code").asText()).isEqualTo("PHOTO_STORAGE_UNAVAILABLE");
		doAnswer(call->links(call.getArgument(0))).when(storage).sign(anyList());
		read(subject,dog,null,null,200);
		assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.dog_photos WHERE dog_id=?",Integer.class,dog)).isEqualTo(1);
	}
	private void changeVisibility(String state) {
		switch(state) {
			case "dog-private" -> jdbc.update("UPDATE shelter.dogs SET is_public=false WHERE id=?",dog);
			case "shelter-private" -> jdbc.update("UPDATE shelter.shelters SET is_public=false WHERE id=?",shelter);
			case "shelter-suspended" -> jdbc.update("UPDATE shelter.shelters SET is_public=false,approval_status='SUSPENDED' WHERE id=?",shelter);
			case "archived" -> jdbc.update("UPDATE shelter.dogs SET archived_at=now() WHERE id=?",dog);
			default -> jdbc.update("UPDATE shelter.dogs SET adoption_status=? WHERE id=?",state,dog);
		}
	}
	private UUID photo(UUID target,int order,String rights,String caption) {
		UUID id=UUID.randomUUID();
		jdbc.update("INSERT INTO shelter.dog_photos(id,dog_id,storage_bucket,storage_key,sort_order,caption,source_note,rights_status,rights_note,rights_confirmed_by,rights_confirmed_at) VALUES (?,?,'dog-photos',?,?,?,'internal-only',?,'internal-only',?,now())",id,target,target+"/"+id+".jpg",order,caption,rights,user);
		return id;
	}
	private void conversation(UUID owner,UUID target,boolean completed) {
		session=UUID.randomUUID(); request=UUID.randomUUID();
		jdbc.update("INSERT INTO shelter.chat_sessions(id,user_id,dog_id) VALUES (?,?,?)",session,owner,target);
		jdbc.update("INSERT INTO shelter.chat_messages(id,session_id,dog_id,role,content,client_message_id,processing_status) VALUES (?,?,?,'USER','안녕','q',?)",request,session,target,completed?"COMPLETED":"PENDING");
		if(completed) answer(session,request,target);
	}
	private void answer(UUID room,UUID question,UUID target) {
		jdbc.update("INSERT INTO shelter.chat_messages(session_id,dog_id,role,content,reply_to_message_id,processing_status) VALUES (?,?,'ASSISTANT','반가워',?,'COMPLETED')",room,target,question);
	}
	private Map<UUID,Signed> links(List<Stored> photos) {
		var result=new HashMap<UUID,Signed>(); for(var p:photos) result.put(p.id(),new Signed("https://signed.example.invalid/"+p.id(),Instant.now().plusSeconds(60))); return result;
	}
	private JsonNode read(UUID who,UUID target,String cursor,String limit,int status) throws Exception {
		var req=get("/v1/dogs/"+target+"/photos").header("Authorization","Bearer "+tokens.token(who));
		if(cursor!=null) req.param("cursor",cursor); if(limit!=null) req.param("limit",limit);
		var response=mvc.perform(req).andExpect(status().is(status)).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse();
		return json.readTree(response.getContentAsString());
	}
}
