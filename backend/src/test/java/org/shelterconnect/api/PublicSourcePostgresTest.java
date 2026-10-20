package org.shelterconnect.api;

import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shelterconnect.api.auth.*;
import org.shelterconnect.api.asset.AssetStore;
import org.shelterconnect.api.management.PublicAnimalClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest @AutoConfigureMockMvc @ActiveProfiles("test")
@Import(JwtTestConfiguration.class) @Transactional
class PublicSourcePostgresTest {
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired JwtTestSupport tokens;
    @Autowired JsonMapper json; @Autowired AssetStore assets;
    @MockitoBean PublicAnimalClient upstream;
    UUID user,subject,other,otherSubject,scope,dog; String animal="442418202601187",care="342418201300001";
    @BeforeAll static void migrate() throws Exception { SchemaMigrationTest.migratePostgres(); }
    @BeforeEach void fixture() {
        user=UUID.randomUUID();subject=UUID.randomUUID();other=UUID.randomUUID();otherSubject=UUID.randomUUID();
        scope=UUID.randomUUID();dog=UUID.randomUUID();
        for(var pair:List.of(new UUID[]{user,subject},new UUID[]{other,otherSubject}))
            jdbc.update("INSERT INTO shelter.app_users(id,display_name,auth_provider,auth_subject) VALUES (?,'담당자',?,?)",
                pair[0],tokens.properties.providerKey(),pair[1].toString());
        jdbc.update("INSERT INTO shelter.shelters(id,name,region,approval_status,is_public,reviewed_by,reviewed_at) VALUES (?,'관리 범위','테스트','APPROVED',true,?,now())",scope,user);
        jdbc.update("INSERT INTO shelter.shelter_memberships(user_id,shelter_id,status) VALUES (?,?,'ACTIVE')",user,scope);
        addDog(dog,animal);when(upstream.fetch(animal)).thenReturn(record(animal,care));
    }
    void addDog(UUID id,String aid) {
        jdbc.update("INSERT INTO shelter.dogs(id,shelter_id,name,avatar_key,introduction) VALUES (?,?,'샘플','same-sprite',?)",id,scope,"public animal "+aid+". 원 보호소: 원본 보호소");
    }
    ObjectNode record(String aid,String code) { return json.createObjectNode().put("desertionNo",aid).put("careRegNo",code)
        .put("careNm","실제 보호소").put("careAddr","강원특별자치도 춘천시 공공 주소").put("careTel","033-000-0000")
        .put("orgNm","강원특별자치도 춘천시").put("processState","보호중"); }
    String path() { return "/v1/shelter-admin/dogs/"+dog+"/public-source"; }
    ObjectNode request() { return json.createObjectNode().put("animalId",animal).put("expectedUpdatedAt",
        jdbc.queryForObject("SELECT updated_at FROM shelter.dogs WHERE id=?",java.sql.Timestamp.class,dog).toInstant().toString()); }
    MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder r,UUID sub) { return r.header("Authorization","Bearer "+tokens.token(sub)); }
    JsonNode link() throws Exception { return json.readTree(mvc.perform(auth(post(path()),subject).contentType("application/json").content(request().toString()))
        .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).get("data"); }
    @Test void actualShelterIsStoredWhilePrivateScopeAndExistingManagementRemainValid() throws Exception {
        var source=link();String real=source.get("shelterId").asText();
        assertThat(real).isNotEqualTo(scope.toString());assertThat(source.get("managementShelterId").asText()).isEqualTo(scope.toString());
        assertThat(source.get("address").asText()).contains("춘천시");
        assertThat(jdbc.queryForObject("SELECT shelter_id::text FROM shelter.dogs WHERE id=?",String.class,dog)).isEqualTo(real);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.shelter_memberships WHERE shelter_id=?",Integer.class,UUID.fromString(real))).isZero();
        mvc.perform(auth(get("/v1/shelter-admin/dogs/"+dog),subject)).andExpect(status().isOk()).andExpect(jsonPath("$.data.shelterId").value(real));
        ObjectNode changes=request();changes.remove("animalId");changes.put("name","같은 강아지");
        mvc.perform(auth(patch("/v1/shelter-admin/dogs/"+dog),subject).contentType("application/json").content(changes.toString())).andExpect(status().isOk());
        mvc.perform(auth(get("/v1/shelter-admin/shelters/"+scope+"/dogs"),subject)).andExpect(status().isOk()).andExpect(jsonPath("$.data[0].id").value(dog.toString()));
        mvc.perform(get("/v1/dogs/"+dog)).andExpect(status().isNotFound());
        mvc.perform(get("/v1/shelters/"+real)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/v1/shelter-admin/shelters/"+real+"/access"),subject)).andExpect(status().isForbidden());
        assertThat(link().get("shelterId").asText()).isEqualTo(real);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void shelterCodeDeduplicatesNamesAndDistinguishesNamesakes(boolean differentCode) throws Exception {
        var first=link();dog=UUID.randomUUID();animal="442418202601188";addDog(dog,animal);
        when(upstream.fetch(animal)).thenReturn(record(animal,differentCode?"342418201300002":care));
        var second=link();
        assertThat(second.get("shelterId").asText().equals(first.get("shelterId").asText())).isEqualTo(!differentCode);
    }
    @Test void sourceLinkDoesNotGrantOtherUsersAccessOrExposePrivateRecords() throws Exception {
        mvc.perform(get(path())).andExpect(status().isUnauthorized());
        mvc.perform(auth(post(path()),otherSubject).contentType("application/json").content(request().toString())).andExpect(status().isForbidden());
        verifyNoInteractions(upstream);link();
        mvc.perform(auth(get(path()),otherSubject)).andExpect(status().isForbidden());
        jdbc.update("UPDATE shelter.shelter_memberships SET status='REVOKED' WHERE user_id=?",user);
        mvc.perform(auth(get(path()),subject)).andExpect(status().isForbidden());
    }
    @ParameterizedTest @ValueSource(strings={"stale","wrong-id","public","archived","active-job","queued-job","waiting-lease","bad-code","duplicate"})
    void invalidLinksAreRejected(String scenario) throws Exception {
        var body=request();String expected;
        switch(scenario) {
            case "stale" -> { body.put("expectedUpdatedAt","2000-01-01T00:00:00Z");expected="STALE_RESOURCE"; }
            case "wrong-id" -> { jdbc.update("UPDATE shelter.dogs SET introduction='different animal' WHERE id=?",dog);body=request();expected="PUBLIC_SOURCE_IDENTITY_MISMATCH"; }
            case "public" -> { jdbc.update("UPDATE shelter.dogs SET is_public=true WHERE id=?",dog);body=request();expected="PUBLIC_SOURCE_PRIVATE_REQUIRED"; }
            case "archived" -> { jdbc.update("UPDATE shelter.dogs SET archived_at=now() WHERE id=?",dog);body=request();expected="PUBLIC_SOURCE_PRIVATE_REQUIRED"; }
            case "active-job" -> { job("RUNNING");expected="PUBLIC_SOURCE_JOB_ACTIVE"; }
            case "queued-job" -> { job("QUEUED");expected="PUBLIC_SOURCE_JOB_ACTIVE"; }
            case "waiting-lease" -> {
                UUID waiting=job("QUEUED");
                jdbc.update("UPDATE shelter.asset_jobs SET failure_code='PUBLIC_SOURCE_LINK_REQUIRED',lease_token=?,lease_until=now()+interval '1 minute' WHERE id=?",UUID.randomUUID(),waiting);
                expected="PUBLIC_SOURCE_JOB_ACTIVE";
            }
            case "bad-code" -> { when(upstream.fetch(animal)).thenReturn(record(animal,"not-a-code"));expected="PUBLIC_SOURCE_INVALID"; }
            default -> { link();dog=UUID.randomUUID();addDog(dog,animal);body=request();expected="PUBLIC_SOURCE_DUPLICATE"; }
        }
        mvc.perform(auth(post(path()),subject).contentType("application/json").content(body.toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(expected));
    }
    UUID job(String state) {
        UUID photo=UUID.randomUUID(),permission=UUID.randomUUID(),job=UUID.randomUUID();
        jdbc.update("INSERT INTO shelter.dog_photos(id,dog_id,storage_bucket,storage_key,sort_order,rights_status,rights_note,rights_confirmed_by,rights_confirmed_at) VALUES (?,?,'photos','unchanged.png',0,'GRANTED','existing permission',?,now())",photo,dog,user);
        jdbc.update("INSERT INTO shelter.asset_source_permissions(id,shelter_id,source_key,source_kind,permission_note,derivatives_allowed,pixellab_allowed,recorded_by) VALUES (?,?,?,'SHELTER','existing',true,true,?)",permission,scope,"source-"+dog,user);
        jdbc.update("INSERT INTO shelter.asset_photo_sources(photo_id,permission_id,storage_bucket,storage_key) VALUES (?,?,'photos','unchanged.png')",photo,permission);
        jdbc.update("INSERT INTO shelter.asset_jobs(id,photo_id,dog_id,shelter_id,permission_id,pipeline_version,status,reviewed_by,reviewed_at) VALUES (?,?,?,?,?,'existing-test',?,?,now())",job,photo,dog,scope,permission,state,user);
        jdbc.update("INSERT INTO shelter.asset_steps(job_id,ordinal,action,status,result) VALUES (?,0,'BASE','SUCCEEDED','{\"storageKey\":\"existing-dot.png\",\"hash\":\"unchanged\"}'::jsonb)",job);
        return job;
    }
    @Test void spriteJobsAndPermissionsAreByteForBytePreservedAndRemainReadable() throws Exception {
        UUID job=job("APPROVED");
        String before=jdbc.queryForObject("SELECT row_to_json(j)::text FROM shelter.asset_jobs j WHERE id=?",String.class,job);
        String step=jdbc.queryForObject("SELECT row_to_json(s)::text FROM shelter.asset_steps s WHERE job_id=?",String.class,job);
        link();assets.checkPreview(subject,dog,job);
        assertThat(jdbc.queryForObject("SELECT row_to_json(j)::text FROM shelter.asset_jobs j WHERE id=?",String.class,job)).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT row_to_json(s)::text FROM shelter.asset_steps s WHERE job_id=?",String.class,job)).isEqualTo(step);
        // A public management scope must never expose an unapproved physical shelter's dog assets.
        jdbc.update("UPDATE shelter.dogs SET is_public=true,adoption_status='AVAILABLE' WHERE id=?",dog);
        assertThatThrownBy(()->assets.available(dog)).isInstanceOf(RuntimeException.class);
    }
    @Test void runtimeRoleCanLinkWithoutApprovalOrMembershipPrivileges() throws Exception {
        jdbc.execute("SET LOCAL ROLE shelter_runtime");
        var linked=link();assertThat(linked.get("careRegNo").asText()).isEqualTo(care);
        assertThat(jdbc.queryForObject("SELECT has_column_privilege(current_user,'shelter.shelters','approval_status','UPDATE')",Boolean.class)).isFalse();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege(current_user,'shelter.shelter_memberships','INSERT')",Boolean.class)).isFalse();
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void exactFiftyDogMigrationLinksTwentyFiveSheltersWithoutTouchingJobs(boolean rejectLast) throws Exception {
        var sql=java.nio.file.Files.readString(java.nio.file.Path.of("src/main/resources/db/migration/V27__link_existing_public_batch.sql"));
        var payload=json.readTree(sql.split("\\$b105\\$")[1]);
        scope=UUID.fromString("02100000-0000-4000-8000-000000000001");
        jdbc.update("INSERT INTO shelter.shelters(id,name,region,approval_status,reviewed_by,reviewed_at) VALUES (?,'검증용 원래 범위','테스트','APPROVED',?,now())",scope,user);
        jdbc.update("INSERT INTO shelter.shelter_memberships(user_id,shelter_id,status) VALUES (?,?,'ACTIVE')",user,scope);
        var jobs=new ArrayList<UUID>();
        for(var row:payload) {
            dog=UUID.fromString(row.get("dogId").asText());addDog(dog,row.get("animalId").asText());jobs.add(job("APPROVED"));
        }
        String before=jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(j) ORDER BY id)::text FROM shelter.asset_jobs j WHERE shelter_id=?",String.class,scope);
        if(rejectLast) {
            jdbc.update("UPDATE shelter.dogs SET is_public=true WHERE id=?",dog);
            jdbc.execute((org.springframework.jdbc.core.ConnectionCallback<Void>) connection->{
                var point=connection.setSavepoint();
                try {
                    assertThatThrownBy(()->jdbc.execute(sql)).isInstanceOf(org.springframework.dao.DataAccessException.class);
                } finally { connection.rollback(point);connection.releaseSavepoint(point); }
                return null;
            });
            assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.public_animal_sources",Integer.class)).isZero();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.dogs WHERE shelter_id=? AND management_shelter_id IS NULL",Integer.class,scope)).isEqualTo(50);
            return;
        }
        jdbc.execute(sql);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.dogs WHERE management_shelter_id=? AND shelter_id<>?",Integer.class,scope,scope)).isEqualTo(50);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT shelter_id) FROM shelter.public_animal_sources",Integer.class)).isEqualTo(25);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.dogs WHERE management_shelter_id=? AND is_public",Integer.class,scope)).isZero();
        assertThat(jdbc.queryForObject("SELECT jsonb_agg(to_jsonb(j) ORDER BY id)::text FROM shelter.asset_jobs j WHERE shelter_id=?",String.class,scope)).isEqualTo(before);
        for(var row:payload) {
            dog=UUID.fromString(row.get("dogId").asText());
            mvc.perform(auth(get(path()),subject)).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.careRegNo").value(row.at("/record/careRegNo").asText()));
        }
    }

}
