package org.shelterconnect.api;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
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
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@Import(JwtTestConfiguration.class) @Transactional
class ManagementPostgresTest {
	private static final UUID USER=id("020",2), OTHER=id("020",3), VISITOR=id("020",4), OPERATOR=id("020",1);
	private static final UUID ONGI=id("021",1), DAON=id("021",2), BOMI=id("022",1), HAERI=id("022",5);
	private static final UUID SUBJECT=UUID.randomUUID(), OTHER_SUB=UUID.randomUUID(), VISITOR_SUB=UUID.randomUUID(), OPERATOR_SUB=UUID.randomUUID();
	@Autowired MockMvc mvc; @Autowired JdbcTemplate jdbc; @Autowired DataSource dataSource;
	@Autowired JsonMapper json; @Autowired JwtTestSupport tokens;
	@BeforeAll static void migrate() throws Exception { SchemaMigrationTest.migratePostgres(); }
	@BeforeEach void fixtures() throws Exception {
		var connection=DataSourceUtils.getConnection(dataSource);
		try { SampleDataLoader.insert(connection, SampleDataLoader.read(Path.of("sample-data/dataset.json"))); }
		finally { DataSourceUtils.releaseConnection(connection,dataSource); }
		link(USER,SUBJECT); link(OTHER,OTHER_SUB); link(VISITOR,VISITOR_SUB); link(OPERATOR,OPERATOR_SUB);
	}

	@ParameterizedTest @ValueSource(strings={"MANAGER","STAFF"})
	void activeStaffCanCreateReadAndUpdateTheirDogs(String role) throws Exception {
		jdbc.update("UPDATE shelter.shelter_memberships SET role=? WHERE user_id=?",role,USER);
		var created=body(auth(post("/v1/shelter-admin/dogs"),SUBJECT,createPayload(ONGI)),201).get("data");
		assertThat(created.get("name").asText()).isEqualTo("새봄");
		assertThat(created.get("isPublic").asBoolean()).isFalse();
		assertThat(created.get("adoptionStatus").asText()).isEqualTo("PAUSED");
		assertThat(created.get("neutered").isNull()).isTrue();
		assertThat(created.get("birthDate").isNull()).isTrue();
		String path="/v1/shelter-admin/dogs/"+created.get("id").asText();
		assertThat(body(auth(get(path),SUBJECT),200).get("data")).isEqualTo(created);
		mvc.perform(get("/v1/dogs/"+created.get("id").asText())).andExpect(status().isNotFound());
		var changes=patchPayload(created).put("name","수정한 이름").put("breed","믹스").put("weightKg",8.25)
				.put("neutered",false).put("birthDate","2023-01-01").put("birthDatePrecision","YEAR").put("birthDateEstimated",true);
		changes.putArray("traitLabels").add("차분해요");
		var updated=body(auth(patch(path),SUBJECT,changes),200).get("data");
		assertThat(updated.get("weightKg").decimalValue()).isEqualByComparingTo("8.25");
		assertThat(updated.get("neutered").asBoolean()).isFalse();
		assertThat(updated.get("birthDatePrecision").asText()).isEqualTo("YEAR");
		assertThat(updated.get("traitLabels").get(0).asText()).isEqualTo("차분해요");
		var clear=patchPayload(updated).putNull("weightKg").putNull("neutered").putNull("birthDate")
				.put("birthDatePrecision","UNKNOWN").putNull("birthDateEstimated");
		var cleared=body(auth(patch(path),SUBJECT,clear),200).get("data");
		assertThat(cleared.get("weightKg").isNull()).isTrue();
		assertThat(cleared.get("breed").asText()).isEqualTo("믹스");
	}
	@Test void publicationAndAdoptionChangesImmediatelyAffectPublicResults() throws Exception {
		JsonNode current=body(auth(get(dog(HAERI)),OTHER_SUB),200).get("data");
		for (String status:List.of("AVAILABLE","IN_PROGRESS","ADOPTED","PAUSED")) {
			current=body(auth(patch(dog(HAERI)),OTHER_SUB,patchPayload(current).put("isPublic",true).put("adoptionStatus",status)),200).get("data");
			boolean visible=status.equals("AVAILABLE")||status.equals("IN_PROGRESS");
			mvc.perform(get("/v1/dogs/"+HAERI)).andExpect(status().is(visible?200:404));
			mvc.perform(get("/v1/shelters/"+DAON)).andExpect(jsonPath("$.data.dogCount").value(visible?2:1));
		}
		current=body(auth(patch(dog(HAERI)),OTHER_SUB,patchPayload(current).put("adoptionStatus","AVAILABLE").put("isPublic",false)),200).get("data");
		mvc.perform(get("/v1/dogs/"+HAERI)).andExpect(status().isNotFound());
	}
	@Test void stalePatchReturnsConflictWithoutOverwritingNewerData() throws Exception {
		var original=body(auth(get(dog(BOMI)),SUBJECT),200).get("data");
		var updated=body(auth(patch(dog(BOMI)),SUBJECT,patchPayload(original).put("name","먼저 저장")),200).get("data");
		assertThat(updated.get("updatedAt").asText()).isNotEqualTo(original.get("updatedAt").asText());
		mvc.perform(auth(patch(dog(BOMI)),SUBJECT,patchPayload(original).put("name","오래된 수정")))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("STALE_RESOURCE"));
		assertThat(jdbc.queryForObject("SELECT name FROM shelter.dogs WHERE id=?",String.class,BOMI)).isEqualTo("먼저 저장");
	}
	@Test void invalidAndForgedUpdatesDoNotChangeStoredData() throws Exception {
		var original=body(auth(get(dog(BOMI)),SUBJECT),200).get("data");
		for(var patch:List.of(patchPayload(original).put("shelterId",DAON.toString()),patchPayload(original).put("weightKg",-3),
				patchPayload(original).put("birthDate","2099-01-01"),json.createObjectNode().put("name","버전 없음"))) {
			mvc.perform(auth(patch(dog(BOMI)),SUBJECT,patch)).andExpect(status().isBadRequest());
		}
		assertThat(body(auth(get(dog(BOMI)),SUBJECT),200).get("data")).isEqualTo(original);
	}
	@Test void visitorsOperatorsAndOtherSheltersCannotWrite() throws Exception {
		int count=jdbc.queryForObject("SELECT count(*) FROM shelter.dogs",Integer.class);
		for(UUID subject:List.of(VISITOR_SUB,OPERATOR_SUB,OTHER_SUB)) {
			mvc.perform(auth(post("/v1/shelter-admin/dogs"),subject,createPayload(ONGI))).andExpect(status().isForbidden());
			mvc.perform(auth(patch(dog(BOMI)),subject,json.createObjectNode().put("name","침범"))).andExpect(status().isForbidden());
			mvc.perform(auth(post(dog(BOMI)+"/observations"),subject,observationPayload())).andExpect(status().isForbidden());
		}
		assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.dogs",Integer.class)).isEqualTo(count);
	}
	@ParameterizedTest @ValueSource(strings={"INVITED","REVOKED","PENDING","REJECTED","SUSPENDED","DISABLED"})
	void revokedOrUnapprovedAccessCannotCreateOrMutate(String state) throws Exception {
		if(state.equals("DISABLED")) jdbc.update("UPDATE shelter.app_users SET disabled_at=now() WHERE id=?",USER);
		else if(state.equals("INVITED")||state.equals("REVOKED")) jdbc.update("UPDATE shelter.shelter_memberships SET status=? WHERE user_id=?",state,USER);
		else jdbc.update("UPDATE shelter.shelters SET is_public=false, approval_status=? WHERE id=?",state,ONGI);
		mvc.perform(auth(post("/v1/shelter-admin/dogs"),SUBJECT,createPayload(ONGI))).andExpect(status().isForbidden());
		mvc.perform(auth(patch(dog(BOMI)),SUBJECT,json.createObjectNode().put("name","차단"))).andExpect(status().isForbidden());
		mvc.perform(auth(post(dog(BOMI)+"/observations"),SUBJECT,observationPayload())).andExpect(status().isForbidden());
		assertThat(jdbc.queryForObject("SELECT name FROM shelter.dogs WHERE id=?",String.class,BOMI)).isEqualTo("봄이");
	}
	@Test void adminPagesIncludePrivateDogsButRemainScopedAndPaginated() throws Exception {
		jdbc.update("UPDATE shelter.shelters SET is_public=false WHERE id=?",DAON);
		String path="/v1/shelter-admin/shelters/"+DAON+"/dogs";
		var first=body(auth(get(path).param("limit","1"),OTHER_SUB),200);
		var second=body(auth(get(path).param("cursor",first.get("nextCursor").asText()),OTHER_SUB),200);
		assertThat(second.at("/data/0/id").asText()).isEqualTo(HAERI.toString());
		assertThat(second.at("/data/0/isPublic").asBoolean()).isFalse();
		assertThat(second.get("nextCursor").isNull()).isTrue();
		mvc.perform(auth(get(path),SUBJECT)).andExpect(status().isForbidden());
		mvc.perform(auth(get("/v1/shelter-admin/shelters/"+ONGI+"/dogs").param("cursor",first.get("nextCursor").asText()),SUBJECT))
				.andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_CURSOR"));
	}
	@Test void observationsPreserveAuthorAndConfirmationWhileSupportingDraftEditAndRetraction() throws Exception {
		String path=dog(BOMI)+"/observations";
		var draft=body(auth(post(path),SUBJECT,observationPayload()),201).get("data");
		assertThat(draft.get("recordedBy").asText()).isEqualTo(USER.toString());
		assertThat(draft.get("status").asText()).isEqualTo("DRAFT");
		assertThat(draft.get("confirmedBy").isNull()).isTrue();
		path+="/"+draft.get("id").asText();
		var edit=body(auth(patch(path),SUBJECT,patchPayload(draft).put("content","짧게 공을 따라갔어요.").putNull("sourceNote")),200).get("data");
		mvc.perform(auth(patch(path),SUBJECT,patchPayload(draft).put("status","CONFIRMED"))).andExpect(status().isConflict());
		jdbc.update("INSERT INTO shelter.shelter_memberships(user_id,shelter_id,role,status) VALUES (?,?,'STAFF','ACTIVE')",OTHER,ONGI);
		var confirmed=body(auth(patch(path),OTHER_SUB,patchPayload(edit).put("status","CONFIRMED")),200).get("data");
		assertThat(confirmed.get("recordedBy").asText()).isEqualTo(USER.toString());
		assertThat(confirmed.get("confirmedBy").asText()).isEqualTo(OTHER.toString());
		assertThat(confirmed.get("confirmedAt").isNull()).isFalse();
		mvc.perform(auth(patch(path),SUBJECT,patchPayload(confirmed).put("content","확인 내용 덮어쓰기")))
				.andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("OBSERVATION_LOCKED"));
		var retracted=body(auth(patch(path),SUBJECT,patchPayload(confirmed).put("status","RETRACTED")),200).get("data");
		assertThat(retracted.get("confirmedBy")).isEqualTo(confirmed.get("confirmedBy"));
		assertThat(retracted.get("content")).isEqualTo(confirmed.get("content"));
		mvc.perform(auth(patch(path),SUBJECT,patchPayload(retracted).put("status","DRAFT"))).andExpect(status().isConflict());
	}
	@Test void observationIdsCannotEscapeTheirDogAndServerAuditFieldsAreNotAccepted() throws Exception {
		String path=dog(HAERI)+"/observations";
		var other=body(auth(post(path),OTHER_SUB,observationPayload().put("status","CONFIRMED")),201).get("data");
		assertThat(other.get("confirmedBy").asText()).isEqualTo(OTHER.toString());
		mvc.perform(auth(patch(dog(BOMI)+"/observations/"+other.get("id").asText()),SUBJECT,patchPayload(other).put("status","RETRACTED")))
				.andExpect(status().isNotFound());
		mvc.perform(auth(post(dog(BOMI)+"/observations"),SUBJECT,observationPayload().put("recordedBy",OTHER.toString())))
				.andExpect(status().isBadRequest());
		var page=body(auth(get(path).param("limit","1"),OTHER_SUB),200);
		assertThat(page.get("data").size()).isEqualTo(1);
		assertThat(page.at("/data/0/dogId").asText()).isEqualTo(HAERI.toString());
		mvc.perform(auth(get(path),SUBJECT)).andExpect(status().isForbidden());
		mvc.perform(get("/v1/dogs/"+BOMI)).andExpect(jsonPath("$.data.observations").doesNotExist());
	}
	@Test void archivedDogsAreReadableToOwnersButCannotBeMutated() throws Exception {
		jdbc.update("UPDATE shelter.dogs SET archived_at=now() WHERE id=?",BOMI);
		var dog=body(auth(get(dog(BOMI)),SUBJECT),200).get("data");
		mvc.perform(auth(patch(dog(BOMI)),SUBJECT,patchPayload(dog).put("name","수정"))).andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("DOG_ARCHIVED"));
		mvc.perform(auth(post(dog(BOMI)+"/observations"),SUBJECT,observationPayload())).andExpect(status().isConflict());
	}
	private ObjectNode createPayload(UUID shelter) { return json.createObjectNode().put("shelterId",shelter.toString()).put("name","새봄").put("avatarKey","bomi"); }
	private ObjectNode observationPayload() { return json.createObjectNode().put("category","PLAY").put("content","공을 따라갔어요.").put("observedAt","2026-09-01T10:00:00+09:00").put("sourceNote","운동장 관찰"); }
	private ObjectNode patchPayload(JsonNode current) { return json.createObjectNode().put("expectedUpdatedAt",current.get("updatedAt").asText()); }
	private String dog(UUID id) { return "/v1/shelter-admin/dogs/"+id; }
	private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req,UUID subject) { return req.header("Authorization","Bearer "+tokens.token(subject)); }
	private MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder req,UUID subject,JsonNode body) { return auth(req,subject).contentType("application/json").content(body.toString()); }
	private JsonNode body(MockHttpServletRequestBuilder req,int code) throws Exception {
		return json.readTree(mvc.perform(req).andExpect(status().is(code)).andExpect(header().string("Cache-Control","no-store"))
				.andReturn().getResponse().getContentAsString());
	}
	private void link(UUID user,UUID subject) { jdbc.update("UPDATE shelter.app_users SET auth_provider=?,auth_subject=? WHERE id=?",tokens.properties.providerKey(),subject.toString(),user); }
	private static UUID id(String prefix,int suffix) { return UUID.fromString(prefix+"00000-0000-4000-8000-"+String.format("%012d",suffix)); }
}
