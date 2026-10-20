package org.shelterconnect.api;

import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.shelterconnect.api.auth.*;
import org.shelterconnect.api.asset.*;
import org.shelterconnect.api.management.PublicAnimalClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Tag("postgres") @SpringBootTest(properties={"app.assets.enabled=true","app.assets.auto-import=true","app.assets.api-key=test-key","app.assets.storage-secret=sb_secret_testing"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(JwtTestConfiguration.class)
class PublicRegistrationPostgresTest {
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc; @Autowired JwtTestSupport tokens;
    @Autowired JsonMapper json; @Autowired AssetStore legacy; @Autowired StyledAssetStore styled;
    @MockitoBean PublicAnimalClient upstream;
    UUID user,subject,other,otherSubject,scope;String animal,care;
    @BeforeAll static void migrate() throws Exception { SchemaMigrationTest.migratePostgres(); }
    @BeforeEach void fixture() {
        user=UUID.randomUUID();subject=UUID.randomUUID();other=UUID.randomUUID();otherSubject=UUID.randomUUID();scope=UUID.randomUUID();
        animal=number();care=number();
        for(var pair:List.of(new UUID[]{user,subject},new UUID[]{other,otherSubject}))
            jdbc.update("INSERT INTO shelter.app_users(id,display_name,auth_provider,auth_subject) VALUES (?,'테스트 담당자',?,?)",pair[0],tokens.properties.providerKey(),pair[1].toString());
        jdbc.update("INSERT INTO shelter.shelters(id,name,region,approval_status,reviewed_by,reviewed_at) VALUES (?,'생성 관리 범위','테스트','APPROVED',?,now())",scope,user);
        jdbc.update("INSERT INTO shelter.shelter_memberships(user_id,shelter_id,status) VALUES (?,?,'ACTIVE')",user,scope);
        when(upstream.fetch(animal)).thenAnswer(c->{
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();return record();
        });
    }
    static String number() { return String.format(Locale.ROOT,"%015d",Math.floorMod(UUID.randomUUID().getMostSignificantBits(),1_000_000_000_000_000L)); }
    ObjectNode record() { return json.createObjectNode().put("desertionNo",animal).put("careRegNo",care).put("careNm","실제 보호소")
        .put("careAddr","강원특별자치도 춘천시 테스트 주소").put("careTel","033-000-0000").put("orgNm","강원특별자치도 춘천시"); }
    ObjectNode input() { return json.createObjectNode().put("shelterId",scope.toString()).put("name","신규 강아지")
        .put("avatarKey","public-test").put("publicAnimalId",animal).put("isPublic",false); }
    MvcResult create(ObjectNode body,int status) throws Exception { return mvc.perform(post("/v1/shelter-admin/dogs")
        .header("Authorization","Bearer "+tokens.token(subject)).contentType("application/json").content(body.toString()))
        .andExpect(status().is(status)).andReturn(); }
    UUID created(ObjectNode body) throws Exception { return UUID.fromString(json.readTree(create(body,201).getResponse().getContentAsString()).at("/data/id").asText()); }
    int dogCount() { return jdbc.queryForObject("SELECT count(*) FROM shelter.dogs WHERE coalesce(management_shelter_id,shelter_id)=?",Integer.class,scope); }
    UUID photo(UUID dog) {
        UUID photo=UUID.randomUUID(),permission=UUID.randomUUID();
        jdbc.update("INSERT INTO shelter.dog_photos(id,dog_id,storage_bucket,storage_key,sort_order,rights_status,rights_note,rights_confirmed_by,rights_confirmed_at) VALUES (?,?,'photos',?,0,'GRANTED','test',?,now())",photo,dog,photo+".png",user);
        jdbc.update("INSERT INTO shelter.asset_source_permissions(id,shelter_id,source_key,source_kind,permission_note,derivatives_allowed,pixellab_allowed,recorded_by) VALUES (?,?,?,'SHELTER','test',true,true,?)",permission,scope,photo.toString(),user);
        jdbc.update("INSERT INTO shelter.asset_photo_sources(photo_id,permission_id,storage_bucket,storage_key) VALUES (?,?,'photos',?)",photo,permission,photo+".png");
        return photo;
    }
    Map<String,Object> importPhoto(UUID photo) {
        UUID permission=jdbc.queryForObject("SELECT permission_id FROM shelter.asset_photo_sources WHERE photo_id=?",UUID.class,photo);
        jdbc.update("UPDATE shelter.asset_source_permissions SET auto_generate=true WHERE id=?",permission);
        return legacy.photoStored(photo,permission);
    }
    void link(UUID dog,int status) throws Exception {
        String version=jdbc.queryForObject("SELECT updated_at FROM shelter.dogs WHERE id=?",java.sql.Timestamp.class,dog).toInstant().toString();
        mvc.perform(post("/v1/shelter-admin/dogs/"+dog+"/public-source").header("Authorization","Bearer "+tokens.token(subject))
            .contentType("application/json").content(json.createObjectNode().put("animalId",animal).put("expectedUpdatedAt",version).toString()))
            .andExpect(status().is(status));
    }
    @AfterEach void cleanup() {
        var dogs=jdbc.queryForList("SELECT id FROM shelter.dogs WHERE coalesce(management_shelter_id,shelter_id)=?",UUID.class,scope);
        for(UUID dog:dogs) {
            jdbc.update("DELETE FROM shelter.asset_submissions WHERE job_id IN (SELECT id FROM shelter.asset_jobs WHERE dog_id=?)",dog);
            jdbc.update("DELETE FROM shelter.asset_steps WHERE job_id IN (SELECT id FROM shelter.asset_jobs WHERE dog_id=?)",dog);
            jdbc.update("DELETE FROM shelter.styled_asset_steps WHERE job_id IN (SELECT id FROM shelter.asset_jobs WHERE dog_id=?)",dog);
            jdbc.update("DELETE FROM shelter.asset_jobs WHERE dog_id=?",dog);
            jdbc.update("DELETE FROM shelter.asset_photo_sources WHERE photo_id IN (SELECT id FROM shelter.dog_photos WHERE dog_id=?)",dog);
            jdbc.update("DELETE FROM shelter.dog_photos WHERE dog_id=?",dog);
            jdbc.update("DELETE FROM shelter.public_animal_sources WHERE dog_id=?",dog);
            jdbc.update("DELETE FROM shelter.dogs WHERE id=?",dog);
        }
        jdbc.update("DELETE FROM shelter.asset_source_permissions WHERE shelter_id=?",scope);
        jdbc.update("DELETE FROM shelter.shelter_memberships WHERE shelter_id=?",scope);
        jdbc.update("DELETE FROM shelter.shelters WHERE id=? OR public_care_reg_no=?",scope,care);
        jdbc.update("DELETE FROM shelter.app_users WHERE id IN (?,?)",user,other);
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void registrationLinksRealShelterBeforeEitherGenerationPipeline(boolean oldImporter) throws Exception {
        var body=input();if(oldImporter){body.remove("publicAnimalId");body.put("introduction","batch / public animal "+animal+". 원문 특징");}
        UUID dog=created(body);
        var actual=jdbc.queryForMap("SELECT d.shelter_id,d.management_shelter_id,a.care_reg_no FROM shelter.dogs d JOIN shelter.public_animal_sources a ON a.dog_id=d.id WHERE d.id=?",dog);
        assertThat(actual.get("shelter_id")).isNotEqualTo(scope);assertThat(actual.get("management_shelter_id")).isEqualTo(scope);assertThat(actual.get("care_reg_no")).isEqualTo(care);
        UUID photo=photo(dog);var request=json.createObjectNode().put("photoId",photo.toString());
        var automatic=(AssetStore.Job)importPhoto(photo).get("job");
        assertThat(automatic.status()).isEqualTo("QUEUED");
        assertThat(legacy.request(subject,dog,request).id()).isEqualTo(automatic.id());
        assertThat(styled.request(subject,dog,request).status()).isEqualTo("QUEUED");
        verify(upstream,times(1)).fetch(animal);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.shelter_memberships WHERE shelter_id=?",Integer.class,actual.get("shelter_id"))).isZero();
    }
    @Test void failedLookupCreatesNoDogOrGeneration() throws Exception {
        when(upstream.fetch(animal)).thenAnswer(c->new PublicAnimalClient("",json).fetch(animal));
        assertThat(create(input(),503).getResponse().getContentAsString()).contains("PUBLIC_SOURCE_NOT_CONFIGURED");
        assertThat(dogCount()).isZero();
    }
    @Test void invalidVerifiedMetadataRollsBackTheNewDog() throws Exception {
        when(upstream.fetch(animal)).thenReturn(record().put("careRegNo","bad"));
        assertThat(create(input(),409).getResponse().getContentAsString()).contains("PUBLIC_SOURCE_INVALID");
        assertThat(dogCount()).isZero();
    }
    @Test void duplicateSourceDoesNotLeaveAnotherDog() throws Exception {
        created(input());assertThat(create(input(),409).getResponse().getContentAsString()).contains("PUBLIC_SOURCE_DUPLICATE");
        assertThat(dogCount()).isEqualTo(1);
    }
    @Test void scopeIsCheckedBeforeFetchAndRecheckedBeforeSave() throws Exception {
        UUID allowed=subject;subject=otherSubject;create(input(),403);verifyNoInteractions(upstream);subject=allowed;
        when(upstream.fetch(animal)).thenAnswer(c->{jdbc.update("UPDATE shelter.shelter_memberships SET status='REVOKED' WHERE user_id=?",user);return record();});
        create(input(),403);assertThat(dogCount()).isZero();
    }
    @ParameterizedTest @ValueSource(strings={"number","mismatch","multiple","public","unknown"})
    void invalidInputsStopBeforeFetching(String scenario) throws Exception {
        var body=input();int status=400;
        switch(scenario) {
            case "number" -> body.put("publicAnimalId",1);
            case "mismatch" -> body.put("introduction","public animal 000000000000000.");
            case "multiple" -> body.put("introduction","public animal "+animal+". public animal "+animal+".");
            case "public" -> {body.put("isPublic",true);status=409;}
            default -> body.put("surprise","unknown");
        }
        create(body,status);verifyNoInteractions(upstream);assertThat(dogCount()).isZero();
    }
    @Test void ordinaryShelterRegistrationDoesNotCallGovernment() throws Exception {
        var body=input();body.remove("publicAnimalId");UUID dog=created(body);verifyNoInteractions(upstream);
        assertThat(jdbc.queryForObject("SELECT shelter_id FROM shelter.dogs WHERE id=?",UUID.class,dog)).isEqualTo(scope);
    }
    @Test void concurrentRegistrationDeduplicatesThePublicAnimal() throws Exception {
        var ready=new CountDownLatch(2);
        when(upstream.fetch(animal)).thenAnswer(c->{ready.countDown();assertThat(ready.await(10,TimeUnit.SECONDS)).isTrue();return record();});
        String token=tokens.token(subject),body=input().toString();
        try(var pool=Executors.newFixedThreadPool(2)) {
            Callable<Integer> task=()->mvc.perform(post("/v1/shelter-admin/dogs").header("Authorization","Bearer "+token)
                .contentType("application/json").content(body)).andReturn().getResponse().getStatus();
            var a=pool.submit(task);var b=pool.submit(task);
            assertThat(List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS))).containsExactlyInAnyOrder(201,409);
        }
        assertThat(dogCount()).isEqualTo(1);
    }
    @Test void unlinkedQueuedJobsWaitAndResumeWithTheSameIdsAfterSourceLink() throws Exception {
        var body=input();body.remove("publicAnimalId");UUID dog=created(body),photo=photo(dog);
        var request=json.createObjectNode().put("photoId",photo.toString());
        UUID first=legacy.request(subject,dog,request).id(),second=styled.request(subject,dog,request).id();
        jdbc.update("UPDATE shelter.dogs SET introduction=? WHERE id=?","public animal "+animal+".",dog);
        Object legacyWork=legacy.claim(),styledWork=styled.claim();assertThat(legacyWork).isNull();assertThat(styledWork).isNull();
        assertThat(jdbc.queryForList("SELECT failure_code FROM shelter.asset_jobs WHERE dog_id=?",String.class,dog))
            .containsExactly("PUBLIC_SOURCE_LINK_REQUIRED","PUBLIC_SOURCE_LINK_REQUIRED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_submissions WHERE job_id IN (?,?)",Integer.class,first,second)).isZero();
        link(dog,200);
        Object resumedLegacy=legacy.claim(),resumedStyled=styled.claim();assertThat(resumedLegacy).isNotNull();assertThat(resumedStyled).isNotNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_jobs WHERE dog_id=? AND failure_code IS NOT NULL",Integer.class,dog)).isZero();
        assertThat(jdbc.queryForList("SELECT id FROM shelter.asset_jobs WHERE dog_id=?",UUID.class,dog)).containsExactlyInAnyOrder(first,second);
    }
    @Test void unlinkedPhotoCannotEnqueueInEitherPipeline() throws Exception {
        var body=input();body.remove("publicAnimalId");UUID dog=created(body),photo=photo(dog);
        jdbc.update("UPDATE shelter.dogs SET introduction=? WHERE id=?","public animal "+animal+".",dog);
        assertThatThrownBy(()->importPhoto(photo)).isInstanceOf(RuntimeException.class)
            .extracting("code").isEqualTo("PUBLIC_SOURCE_LINK_REQUIRED");
        for(String route:List.of("assets","styled-assets"))mvc.perform(post("/v1/shelter-admin/dogs/"+dog+"/"+route)
            .header("Authorization","Bearer "+tokens.token(subject)).contentType("application/json")
            .content(json.createObjectNode().put("photoId",photo.toString()).toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PUBLIC_SOURCE_LINK_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_jobs WHERE dog_id=?",Integer.class,dog)).isZero();
    }
    @Test void structuredSourceStillGuardsGenerationWhenIntroductionIsEdited() throws Exception {
        UUID dog=created(input()),photo=photo(dog);
        jdbc.update("UPDATE shelter.dogs SET introduction='edited description',shelter_id=? WHERE id=?",scope,dog);
        for(String route:List.of("assets","styled-assets"))mvc.perform(post("/v1/shelter-admin/dogs/"+dog+"/"+route)
            .header("Authorization","Bearer "+tokens.token(subject)).contentType("application/json")
            .content(json.createObjectNode().put("photoId",photo.toString()).toString()))
            .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("PUBLIC_SOURCE_LINK_REQUIRED"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_jobs WHERE dog_id=?",Integer.class,dog)).isZero();
    }
}
