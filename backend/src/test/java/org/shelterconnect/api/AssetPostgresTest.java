package org.shelterconnect.api;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.shelterconnect.api.asset.*;
import org.shelterconnect.api.auth.*;
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

@Tag("postgres") @SpringBootTest(properties={"app.assets.enabled=true","app.assets.auto-import=true","app.assets.api-key=test-key","app.assets.storage-secret=sb_secret_testing"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(JwtTestConfiguration.class)
class AssetPostgresTest {
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc;@Autowired JsonMapper json;@Autowired JwtTestSupport tokens;
    @Autowired AssetWorker worker; @Autowired AssetStore store;
    @MockitoBean AssetProvider provider; @MockitoBean AssetStorage storage;
    UUID operator,user,opSubject,subject,outsiderSubject,shelter,dog,photo;
    Map<String,byte[]> objects=new ConcurrentHashMap<>();byte[] sprite;
    @BeforeAll static void migrate() throws Exception { SchemaMigrationTest.migratePostgres(); }
    @BeforeEach void fixture() throws Exception {
        operator=UUID.randomUUID();user=UUID.randomUUID();opSubject=UUID.randomUUID();subject=UUID.randomUUID();outsiderSubject=UUID.randomUUID();
        shelter=UUID.randomUUID();dog=UUID.randomUUID();photo=UUID.randomUUID();
        jdbc.update("INSERT INTO shelter.app_users(id,display_name,role,auth_provider,auth_subject) VALUES (?,'운영자','OPERATOR',?,?),(?,'보호소','USER',?,?)",operator,tokens.properties.providerKey(),opSubject.toString(),user,tokens.properties.providerKey(),subject.toString());
        jdbc.update("INSERT INTO shelter.shelters(id,name,region,approval_status,is_public,reviewed_by,reviewed_at) VALUES (?,'가상 보호소','가상','APPROVED',true,?,now())",shelter,operator);
        jdbc.update("INSERT INTO shelter.shelter_memberships(user_id,shelter_id,role,status) VALUES (?,?,'MANAGER','ACTIVE')",user,shelter);
        jdbc.update("INSERT INTO shelter.dogs(id,shelter_id,name,avatar_key,is_public,adoption_status) VALUES (?,?,'샘플','sample',true,'AVAILABLE')",dog,shelter);
        jdbc.update("INSERT INTO shelter.dog_photos(id,dog_id,storage_bucket,storage_key,sort_order,rights_status,rights_note,rights_confirmed_by,rights_confirmed_at) VALUES (?,?,'dog-photos',?,0,'GRANTED','가상 허가',?,now())",photo,dog,dog+"/source.png",operator);
        var im=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);for(int y=12;y<60;y++)for(int x=15;x<48;x++)im.setRGB(x,y,0xff996633);
        var bytes=new ByteArrayOutputStream();ImageIO.write(im,"png",bytes);sprite=bytes.toByteArray();
        when(storage.photo(any(),anyString(),anyString())).thenAnswer(c->{noTransaction();return sprite;});
        when(storage.asset(anyString())).thenAnswer(c->{noTransaction();return objects.get(c.getArgument(0));});
        doAnswer(c->{noTransaction();objects.put(c.getArgument(0),c.getArgument(1));return null;}).when(storage).put(anyString(),any());
        when(storage.sign(anyList())).thenAnswer(c->{noTransaction();Map<String,String> out=new LinkedHashMap<>();for(String k:c.<List<String>>getArgument(0))out.put(k,"https://assets.example.invalid/"+k);return out;});
        when(provider.submit(any(),any())).thenAnswer(c->{noTransaction();return UUID.randomUUID();});
        when(provider.poll(any())).thenAnswer(c->{noTransaction();return new AssetProvider.Poll("COMPLETED",Collections.nCopies(16,sprite));});
    }
    @AfterEach void cleanup() {
        jdbc.update("DELETE FROM shelter.asset_submissions WHERE job_id IN (SELECT id FROM shelter.asset_jobs WHERE dog_id=?)",dog);
        jdbc.update("DELETE FROM shelter.asset_steps WHERE job_id IN (SELECT id FROM shelter.asset_jobs WHERE dog_id=?)",dog);
        jdbc.update("DELETE FROM shelter.asset_jobs WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.asset_photo_sources WHERE photo_id=?",photo);
        jdbc.update("DELETE FROM shelter.asset_source_permissions WHERE shelter_id=?",shelter);
        jdbc.update("DELETE FROM shelter.dog_photos WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.dogs WHERE id=?",dog);
        jdbc.update("DELETE FROM shelter.shelter_memberships WHERE shelter_id=?",shelter);
        jdbc.update("DELETE FROM shelter.shelters WHERE id=?",shelter);
        jdbc.update("DELETE FROM shelter.app_users WHERE id IN (?,?)",operator,user);
    }
    @Test void eightActionsAreGeneratedOnceAndPublishedOnlyAfterShelterReview() throws Exception {
        UUID permission=permission(true);UUID job=importPhoto(permission);
        assertThat(importPhoto(permission)).isEqualTo(job);
        for(int i=0;i<18;i++) tick();
        var draft=read(subject,job,200);assertThat(draft.at("/data/status").asText()).isEqualTo("REVIEW");
        assertThat(draft.at("/data/steps").size()).isEqualTo(9);
        verify(provider,times(9)).submit(any(),any());
        mvc.perform(get("/v1/dogs/"+dog+"/assets")).andExpect(status().isNotFound());
        postJson(subject,"/v1/shelter-admin/dogs/"+dog+"/assets/"+job+"/review",Map.of("decision","APPROVE"),200);
        var result=mvc.perform(get("/v1/dogs/"+dog+"/assets")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        JsonNode published=json.readTree(result.getResponse().getContentAsString());
        assertThat(published.at("/data/animations").size()).isEqualTo(8);
        assertThat(published.at("/data/animations/SIT/holdLastFrame").asBoolean()).isTrue();
        assertThat(published.toString()).doesNotContain("photoId","source.png","permissionNote","test-key");
        mvc.perform(delete("/v1/operations/asset-permissions/"+permission).header("Authorization",bearer(opSubject))).andExpect(status().isNoContent());
        mvc.perform(get("/v1/dogs/"+dog+"/assets")).andExpect(status().isNotFound());
    }
    @Test void missingConsentOrdinaryUsersAndOtherSheltersCannotQueueOrRead() throws Exception {
        postJson(subject,"/v1/operations/asset-permissions",permissionBody(true),403);
        postJson(subject,"/v1/shelter-admin/dogs/"+dog+"/assets",Map.of("photoId",photo),409);
        UUID grant=permission(true),job=importPhoto(grant);
        read(outsiderSubject,job,403);
        postJson(subject,"/v1/shelter-admin/dogs/"+dog+"/assets/"+job+"/review",Map.of("decision","APPROVE"),409);
        mvc.perform(post("/v1/shelter-admin/dogs/"+dog+"/assets").contentType("application/json").content("{}" )).andExpect(status().isUnauthorized());
        jdbc.update("UPDATE shelter.dog_photos SET rights_status='REVOKED' WHERE id=?",photo);tick();
        assertThat(read(subject,job,200).at("/data/status").asText()).isEqualTo("CANCELLED");verifyNoInteractions(provider);
    }
    @Test void crawlConsentAndBothAutomaticFlagsAreRequired() throws Exception {
        var denied=new HashMap<>(permissionBody(true));denied.put("crawlAllowed",false);
        postJson(opSubject,"/v1/operations/asset-permissions",denied,400);
        UUID grant=permission(false);
        JsonNode result=postJson(opSubject,"/v1/operations/asset-imports",Map.of("photoId",photo,"permissionId",grant),200);
        assertThat(result.at("/data/status").asText()).isEqualTo("REGISTERED");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_jobs WHERE dog_id=?",Integer.class,dog)).isZero();
        postJson(subject,"/v1/shelter-admin/dogs/"+dog+"/assets",Map.of("photoId",photo),202);
        tick();verify(provider).submit(eq(AssetAction.BASE),any());
    }
    @Test void ambiguousPaidRequestStopsUntilAnOperatorReconcilesItsExistingProviderId() throws Exception {
        UUID job=importPhoto(permission(true));
        when(provider.submit(any(),any())).thenThrow(new AssetProvider.Failure("ACK_LOST",true));tick();tick();
        assertThat(read(subject,job,200).at("/data/status").asText()).isEqualTo("OUTCOME_UNKNOWN");
        postJson(opSubject,"/v1/operations/asset-jobs/"+job+"/retry",Map.of(),409);
        verify(provider,times(1)).submit(any(),any());
        postJson(opSubject,"/v1/operations/asset-jobs/"+job+"/reconcile",Map.of("providerJobId",UUID.randomUUID()),200);
        tick();verify(provider,times(1)).submit(any(),any());
        assertThat(read(subject,job,200).at("/data/steps/0/status").asText()).isEqualTo("SUCCEEDED");
    }
    @Test void workerRestartDoesNotResubmitAnUnacknowledgedStepAndLeasesHaveSingleOwner() throws Exception {
        UUID job=importPhoto(permission(true));var lease=store.claim();assertThat(lease).isNotNull();assertThat(store.claim()).isNull();
        assertThat(store.reserve(lease)).isTrue();
        jdbc.update("UPDATE shelter.asset_jobs SET lease_until=now()-interval '1 second' WHERE id=?",job);
        assertThat(store.claim()).isNull();
        assertThat(read(subject,job,200).at("/data/status").asText()).isEqualTo("OUTCOME_UNKNOWN");verifyNoInteractions(provider);
    }
    @Test void transientStorageFailureReusesProviderResultWithoutNewGeneration() throws Exception {
        UUID job=importPhoto(permission(true));tick();
        doThrow(new RuntimeException("storage offline")).doAnswer(c->{objects.put(c.getArgument(0),c.getArgument(1));return null;}).when(storage).put(anyString(),any());
        tick();
        assertThat(read(subject,job,200).at("/data/status").asText()).isEqualTo("RUNNING");
        tick(); assertThat(read(subject,job,200).at("/data/steps/0/status").asText()).isEqualTo("SUCCEEDED");
        verify(provider,times(1)).submit(any(),any());
    }
    @Test void concurrentImportsDeduplicateAndDailyLimitIncludesRetries() throws Exception {
        UUID grant=permission(true);
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->store.photoStored(photo,grant));var b=pool.submit(()->store.photoStored(photo,grant));
            assertThat(((AssetStore.Job)a.get().get("job")).id()).isEqualTo(((AssetStore.Job)b.get().get("job")).id());
        }
        UUID job=jdbc.queryForObject("SELECT id FROM shelter.asset_jobs WHERE dog_id=?",UUID.class,dog);
        for(int i=0;i<10;i++)jdbc.update("INSERT INTO shelter.asset_submissions(job_id,action) VALUES (?,'BASE')",job);
        tick();verifyNoInteractions(provider);
        assertThat(read(subject,job,200).at("/data/failureCode").asText()).isEqualTo("DAILY_REQUEST_LIMIT");
    }
    @Test void permissionIsRecheckedAfterProviderCompletionAndSignedUrlCreation() throws Exception {
        UUID job=importPhoto(permission(true));tick();
        when(provider.poll(any())).thenAnswer(c->{
            jdbc.update("UPDATE shelter.dog_photos SET rights_status='REVOKED' WHERE id=?",photo);
            return new AssetProvider.Poll("COMPLETED",Collections.nCopies(16,sprite));
        });
        tick();verify(storage,never()).put(anyString(),any());
        assertThat(read(subject,job,200).at("/data/status").asText()).isEqualTo("CANCELLED");
    }
    @Test void revokedSourceCannotLeakSignedManifestAfterStorageResponds() throws Exception {
        UUID job=importPhoto(permission(true));for(int i=0;i<18;i++)tick();
        postJson(subject,"/v1/shelter-admin/dogs/"+dog+"/assets/"+job+"/review",Map.of("decision","APPROVE"),200);
        when(storage.sign(anyList())).thenAnswer(c->{
            jdbc.update("UPDATE shelter.dog_photos SET rights_status='REVOKED' WHERE id=?",photo);
            Map<String,String> links=new HashMap<>();for(String k:c.<List<String>>getArgument(0))links.put(k,"https://assets.example.invalid/"+k);return links;
        });
        var response=mvc.perform(get("/v1/dogs/"+dog+"/assets")).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("assets.example.invalid","spritesheetUrl");
    }
    private void tick() { jdbc.update("UPDATE shelter.asset_jobs SET next_run_at=now() WHERE dog_id=?",dog);worker.tick(); }
    private Map<String,Object> permissionBody(boolean auto) { return Map.of("shelterId",shelter,"sourceKey","fixture-source","sourceKind","CRAWL","permissionNote","TEST ONLY: 허가된 사진의 파생 제작 및 PixelLab 전송 허용","crawlAllowed",true,"derivativesAllowed",true,"pixellabAllowed",true,"autoGenerate",auto); }
    private UUID permission(boolean auto) throws Exception { return UUID.fromString(postJson(opSubject,"/v1/operations/asset-permissions",permissionBody(auto),201).at("/data/id").asText()); }
    private UUID importPhoto(UUID permission) throws Exception { return UUID.fromString(postJson(opSubject,"/v1/operations/asset-imports",Map.of("photoId",photo,"permissionId",permission),200).at("/data/job/id").asText()); }
    private JsonNode read(UUID who,UUID job,int code) throws Exception { return json.readTree(mvc.perform(get("/v1/shelter-admin/dogs/"+dog+"/assets/"+job).header("Authorization",bearer(who))).andExpect(status().is(code)).andReturn().getResponse().getContentAsString()); }
    private JsonNode postJson(UUID who,String path,Object body,int code) throws Exception { return json.readTree(mvc.perform(post(path).header("Authorization",bearer(who)).contentType("application/json").content(json.writeValueAsString(body))).andExpect(status().is(code)).andReturn().getResponse().getContentAsString()); }
    private String bearer(UUID who) { return "Bearer "+tokens.token(who); }
    private static void noTransaction() { assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse(); }
}
