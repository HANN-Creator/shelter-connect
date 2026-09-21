package org.shelterconnect.api;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shelterconnect.api.auth.JwtTestConfiguration;
import org.shelterconnect.api.auth.JwtTestSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Each test owns committed fixtures so HTTP calls and competing transactions use real database boundaries. */
@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test") @Import(JwtTestConfiguration.class)
class BehaviorPostgresTest {
	@Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired JsonMapper json;
	@Autowired JwtTestSupport tokens; @Autowired PlatformTransactionManager manager;
	private UUID user,other,visitor,subject,otherSubject,visitorSubject,shelter,otherShelter,dog,otherDog,observation,otherObservation;
	private ExecutorService executor;
	@BeforeAll static void migrate() throws Exception { SchemaMigrationTest.migratePostgres(); }
	@BeforeEach void fixtures() {
		user=UUID.randomUUID();other=UUID.randomUUID();visitor=UUID.randomUUID();subject=UUID.randomUUID();otherSubject=UUID.randomUUID();visitorSubject=UUID.randomUUID();
		shelter=UUID.randomUUID();otherShelter=UUID.randomUUID();dog=UUID.randomUUID();otherDog=UUID.randomUUID();observation=UUID.randomUUID();otherObservation=UUID.randomUUID();
		executor=Executors.newFixedThreadPool(2);
		link(user,subject);link(other,otherSubject);link(visitor,visitorSubject);
		for(UUID id:List.of(shelter,otherShelter)) jdbc.update("INSERT INTO shelter.shelters(id,name,region,approval_status,reviewed_by,reviewed_at,is_public) VALUES (?,'행동 테스트','가상','APPROVED',?,now(),true)",id,user);
		jdbc.update("INSERT INTO shelter.shelter_memberships(user_id,shelter_id,role,status) VALUES (?,?,'STAFF','ACTIVE'),(?,?,'MANAGER','ACTIVE')",user,shelter,other,otherShelter);
		jdbc.update("INSERT INTO shelter.dogs(id,shelter_id,name,avatar_key,is_public,adoption_status) VALUES (?,?,'테스트 강아지','test',true,'AVAILABLE'),(?,?,'다른 강아지','test',true,'AVAILABLE')",dog,shelter,otherDog,otherShelter);
		jdbc.update("INSERT INTO shelter.dog_observations(id,dog_id,category,content,observed_at,recorded_by,status,confirmed_by,confirmed_at) VALUES (?,?,'PLAY','관찰한 놀이 반응',now(),?,'CONFIRMED',?,now()),(?,?,'PEOPLE','관찰한 거리 반응',now(),?,'CONFIRMED',?,now())",observation,dog,user,user,otherObservation,otherDog,other,other);
	}
	@AfterEach void cleanup() {
		if(executor!=null)executor.close();
		jdbc.update("DELETE FROM shelter.dog_behavior_profiles WHERE dog_id IN (?,?)",dog,otherDog);
		jdbc.update("DELETE FROM shelter.dog_observations WHERE id IN (?,?)",observation,otherObservation);
		jdbc.update("DELETE FROM shelter.dogs WHERE id IN (?,?)",dog,otherDog);
		jdbc.update("DELETE FROM shelter.shelter_memberships WHERE user_id IN (?,?,?)",user,other,visitor);
		jdbc.update("DELETE FROM shelter.shelters WHERE id IN (?,?)",shelter,otherShelter);
		jdbc.update("DELETE FROM shelter.app_users WHERE id IN (?,?,?)",user,other,visitor);
	}
	@Test void absentDraftConfirmedAndEditedStatesHaveExplicitPlayback() throws Exception {
		assertThat(body(auth(get(path()),subject),200).get("data").isNull()).isTrue();
		var defaultSettings=body(get(pub()),200).at("/data/settings");
		assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.dog_behavior_profiles WHERE dog_id=?",Integer.class,dog)).isZero();
		var saved=save(payload(0),200);assertThat(saved.get("revision").asInt()).isEqualTo(1);
		assertThat(saved.get("status").asText()).isEqualTo("DRAFT");assertThat(saved.get("confirmedBy").isNull()).isTrue();
		assertThat(body(get(pub()),200).at("/data/settings")).isEqualTo(defaultSettings);
		var confirmed=confirm(1,200);assertThat(confirmed.get("revision").asInt()).isEqualTo(2);
		assertThat(confirmed.get("confirmedBy").asText()).isEqualTo(user.toString());assertThat(confirmed.get("confirmedAt").isNull()).isFalse();
		assertThat(confirm(2,200)).isEqualTo(confirmed);
		var publicData=body(get(pub()),200).get("data");assertThat(publicData.get("basis").asText()).isEqualTo("CONFIRMED");
		assertThat(publicData.at("/settings/actions/RUN/weight").asInt()).isEqualTo(60);
		for(String field:List.of("source","status","confirmedBy","confirmedAt","evidenceObservationIds"))assertThat(publicData.has(field)).isFalse();
		var edited=save(payload(2),200);assertThat(edited.get("revision").asInt()).isEqualTo(3);
		assertThat(edited.get("confirmedBy").isNull()).isTrue();assertThat(edited.get("confirmedAt").isNull()).isTrue();
		assertThat(body(get(pub()),200).at("/data/basis").asText()).isEqualTo("DEFAULT");
		confirm(3,200);
	}
	@Test void staleSavesAndConfirmationsDoNotOverwriteOrPublish() throws Exception {
		var original=save(payload(0),200);
		save(payload(0),409);confirm(2,409);
		assertThat(body(auth(get(path()),subject),200).get("data")).isEqualTo(original);
		confirm(1,200);save(payload(1),409);confirm(1,409);
	}
	@ParameterizedTest @ValueSource(strings={"empty","other-dog","missing","draft","retracted"})
	void confirmationRequiresCurrentEvidenceFromThisDog(String kind) throws Exception {
		if(kind.equals("empty")) {
			var input=payload(0);input.putArray("evidenceObservationIds");save(input,200);confirm(1,409);return;
		}
		if(kind.equals("other-dog")||kind.equals("missing")) {
			var input=payload(0);input.putArray("evidenceObservationIds").add((kind.equals("other-dog")?otherObservation:UUID.randomUUID()).toString());
			save(input,409);assertThat(body(auth(get(path()),subject),200).get("data").isNull()).isTrue();return;
		}
		save(payload(0),200);
		jdbc.update("UPDATE shelter.dog_observations SET status=? WHERE id=?",kind.equals("draft")?"DRAFT":"RETRACTED",observation);
		confirm(1,409);save(payload(1),409);
		assertThat(body(get(pub()),200).at("/data/basis").asText()).isEqualTo("DEFAULT");
	}
	@Test void withdrawnEvidenceAndLegacyInvalidSettingsNeverReachPlayback() throws Exception {
		save(payload(0),200);confirm(1,200);
		jdbc.update("UPDATE shelter.dog_observations SET status='RETRACTED' WHERE id=?",observation);
		assertThat(body(get(pub()),200).at("/data/basis").asText()).isEqualTo("DEFAULT");
		jdbc.update("UPDATE shelter.dog_observations SET status='CONFIRMED' WHERE id=?",observation);
		jdbc.update("UPDATE shelter.dog_behavior_profiles SET schema_version=2 WHERE dog_id=?",dog);
		assertThat(body(get(pub()),200).at("/data/basis").asText()).isEqualTo("DEFAULT");
		jdbc.update("UPDATE shelter.dog_behavior_profiles SET schema_version=1,settings='{}' WHERE dog_id=?",dog);
		assertThat(body(get(pub()),200).at("/data/basis").asText()).isEqualTo("DEFAULT");
		confirm(2,400);save(payload(2),200);
	}
	@Test void aiSourceIsOnlyAReviewableDraftAndAuditFieldsCannotBeForged() throws Exception {
		var input=payload(0).put("source","AI_SUGGESTED");var saved=save(input,200);
		assertThat(saved.get("status").asText()).isEqualTo("DRAFT");
		assertThat(body(get(pub()),200).at("/data/basis").asText()).isEqualTo("DEFAULT");
		save(payload(1).put("confirmedBy",other.toString()),400);
		save(payload(1).put("status","CONFIRMED"),400);
		assertThat(body(auth(get(path()),subject),200).get("data")).isEqualTo(saved);
	}
	@ParameterizedTest @ValueSource(strings={"INVITED","REVOKED","PENDING","REJECTED","SUSPENDED","DISABLED","other","visitor","operator","unregistered"})
	void onlyActiveApprovedShelterMembersCanReadSaveOrConfirm(String state) throws Exception {
		save(payload(0),200);UUID actor=subject;
		switch(state) {
			case "INVITED","REVOKED"->jdbc.update("UPDATE shelter.shelter_memberships SET status=? WHERE user_id=?",state,user);
			case "PENDING","REJECTED","SUSPENDED"->jdbc.update("UPDATE shelter.shelters SET is_public=false,approval_status=? WHERE id=?",state,shelter);
			case "DISABLED"->jdbc.update("UPDATE shelter.app_users SET disabled_at=now() WHERE id=?",user);
			case "other"->actor=otherSubject;
			case "visitor"->actor=visitorSubject;
			case "operator"->{jdbc.update("UPDATE shelter.app_users SET role='OPERATOR' WHERE id=?",visitor);actor=visitorSubject;}
			case "unregistered"->actor=UUID.randomUUID();
		}
		body(auth(get(path()),actor),403);body(auth(put(path()),actor).contentType("application/json").content(payload(1).toString()),403);
		body(auth(post(path()+"/confirmation"),actor).contentType("application/json").content("{\"expectedRevision\":1}"),403);
		assertThat(jdbc.queryForObject("SELECT revision FROM shelter.dog_behavior_profiles WHERE dog_id=?",Integer.class,dog)).isEqualTo(1);
	}
	@ParameterizedTest @ValueSource(strings={"shelter-private","dog-private","suspended","archived","adopted","paused"})
	void unavailableDogsNeverExposePlayback(String state) throws Exception {
		save(payload(0),200);confirm(1,200);
		switch(state) {
			case "shelter-private"->jdbc.update("UPDATE shelter.shelters SET is_public=false WHERE id=?",shelter);
			case "dog-private"->jdbc.update("UPDATE shelter.dogs SET is_public=false WHERE id=?",dog);
			case "suspended"->jdbc.update("UPDATE shelter.shelters SET is_public=false,approval_status='SUSPENDED' WHERE id=?",shelter);
			case "archived"->jdbc.update("UPDATE shelter.dogs SET archived_at=now() WHERE id=?",dog);
			default->jdbc.update("UPDATE shelter.dogs SET adoption_status=? WHERE id=?",state.equals("adopted")?"ADOPTED":"PAUSED",dog);
		}
		assertThat(body(get(pub()),404).get("code").asText()).isEqualTo("DOG_NOT_FOUND");
		if(state.equals("archived")) {save(payload(2),409);confirm(2,409);}
	}
	@Test void privateDogsCanBeConfiguredAndInProgressDogsCanUseConfirmedPlayback() throws Exception {
		jdbc.update("UPDATE shelter.dogs SET is_public=false,adoption_status='IN_PROGRESS' WHERE id=?",dog);
		jdbc.update("UPDATE shelter.shelter_memberships SET role='MANAGER' WHERE user_id=?",user);
		save(payload(0),200);confirm(1,200);
		jdbc.update("UPDATE shelter.dogs SET is_public=true WHERE id=?",dog);
		assertThat(body(get(pub()),200).at("/data/basis").asText()).isEqualTo("CONFIRMED");
	}
	@Test void simultaneousFirstSavesAndSaveConfirmRacesHaveOneWinner() throws Exception {
		var start=new CountDownLatch(1);
		var first=executor.submit(()->{start.await();return writeCode(put(path()),payload(0));});
		var second=executor.submit(()->{start.await();return writeCode(put(path()),payload(0));});start.countDown();
		assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
		var again=new CountDownLatch(1);
		first=executor.submit(()->{again.await();return writeCode(put(path()),payload(1));});
		second=executor.submit(()->{again.await();return writeCode(post(path()+"/confirmation"),json.createObjectNode().put("expectedRevision",1));});again.countDown();
		assertThat(List.of(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS))).containsExactlyInAnyOrder(200,409);
		assertThat(jdbc.queryForObject("SELECT revision FROM shelter.dog_behavior_profiles WHERE dog_id=?",Integer.class,dog)).isEqualTo(2);
	}
	@ParameterizedTest @ValueSource(strings={"membership","account","shelter","ownership","archive","evidence"})
	void permissionAndEvidenceChangesWhileConfirmingWaitsAreRechecked(String change) throws Exception {
		save(payload(0),200);
		var future=new java.util.concurrent.atomic.AtomicReference<Future<Integer>>();
		new TransactionTemplate(manager).executeWithoutResult(tx->{
			int blocker=jdbc.queryForObject("SELECT pg_backend_pid()",Integer.class);
			switch(change) {
				case "membership"->jdbc.update("UPDATE shelter.shelter_memberships SET status='REVOKED' WHERE user_id=?",user);
				case "account"->jdbc.update("UPDATE shelter.app_users SET disabled_at=now() WHERE id=?",user);
				case "shelter"->jdbc.update("UPDATE shelter.shelters SET is_public=false,approval_status='SUSPENDED' WHERE id=?",shelter);
				case "ownership"->jdbc.update("UPDATE shelter.dogs SET shelter_id=? WHERE id=?",otherShelter,dog);
				case "archive"->jdbc.update("UPDATE shelter.dogs SET archived_at=now() WHERE id=?",dog);
				case "evidence"->jdbc.update("UPDATE shelter.dog_observations SET status='RETRACTED' WHERE id=?",observation);
			}
			future.set(executor.submit(()->writeCode(post(path()+"/confirmation"),json.createObjectNode().put("expectedRevision",1))));
			long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(5);boolean waiting=false;
			while(System.nanoTime()<deadline) {
				waiting=jdbc.queryForObject("SELECT EXISTS (SELECT 1 FROM pg_locks WHERE NOT granted AND ? = ANY(pg_blocking_pids(pid)))",Boolean.class,blocker);
				if(waiting)break;
				try {Thread.sleep(10);}catch(InterruptedException ex){Thread.currentThread().interrupt();throw new IllegalStateException(ex);}
			}
			assertThat(waiting).as("confirmation waits for %s",change).isTrue();
		});
		assertThat(future.get().get(15,TimeUnit.SECONDS)).isEqualTo(Set.of("archive","evidence").contains(change)?409:403);
		assertThat(jdbc.queryForObject("SELECT status FROM shelter.dog_behavior_profiles WHERE dog_id=?",String.class,dog)).isEqualTo("DRAFT");
	}
	@Test void invalidIdsAndBodiesFailWithoutCreatingProfile() throws Exception {
		body(get("/v1/dogs/not-an-id/behavior"),400);
		body(get("/v1/dogs/"+UUID.randomUUID()+"/behavior"),404);
		confirm(1,404);
		var input=payload(0);((ObjectNode)input.at("/settings/actions/WALK")).put("speedTilesPerSecond",-1);save(input,400);
		assertThat(body(auth(get(path()),subject),200).get("data").isNull()).isTrue();
	}
	private ObjectNode payload(int revision) throws Exception {
		var value=json.createObjectNode().put("expectedRevision",revision).put("schemaVersion",1).put("source","SHELTER");
		var settings=(ObjectNode)body(get("/v1/dogs/"+otherDog+"/behavior"),200).at("/data/settings");
		((ObjectNode)settings.at("/actions/RUN")).put("weight",60);
		((ObjectNode)settings.at("/ballPlay")).put("chaseEnabled",true).put("returnEnabled",true);
		value.set("settings",settings);value.putArray("evidenceObservationIds").add(observation.toString());return value;
	}
	private JsonNode save(JsonNode input,int status) throws Exception {return body(auth(put(path()),subject).contentType("application/json").content(input.toString()),status).get("data");}
	private JsonNode confirm(int revision,int status) throws Exception {return body(auth(post(path()+"/confirmation"),subject).contentType("application/json").content("{\"expectedRevision\":"+revision+"}"),status).get("data");}
	private int writeCode(MockHttpServletRequestBuilder req,JsonNode body) throws Exception {return mvc.perform(auth(req,subject).contentType("application/json").content(body.toString())).andReturn().getResponse().getStatus();}
	private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req,UUID actor) {return req.header("Authorization","Bearer "+tokens.token(actor));}
	private JsonNode body(MockHttpServletRequestBuilder req,int expected) throws Exception {
		return json.readTree(mvc.perform(req).andExpect(status().is(expected)).andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsString());
	}
	private void link(UUID id,UUID auth) {jdbc.update("INSERT INTO shelter.app_users(id,display_name,auth_provider,auth_subject) VALUES (?,'행동 검증',?,?)",id,tokens.properties.providerKey(),auth.toString());}
	private String path() {return "/v1/shelter-admin/dogs/"+dog+"/behavior";}
	private String pub() {return "/v1/dogs/"+dog+"/behavior";}
}
