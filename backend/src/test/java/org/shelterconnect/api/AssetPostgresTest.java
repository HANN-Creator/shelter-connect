package org.shelterconnect.api;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.shelterconnect.api.asset.*;
import org.shelterconnect.api.behavior.BehaviorSuggestionProvider;
import org.springframework.mock.web.MockMultipartFile;
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

@Tag("postgres") @SpringBootTest(properties={"app.ai.enabled=true","app.ai.api-key=test-key","app.assets.enabled=true","app.assets.auto-import=true","app.assets.api-key=test-key","app.assets.storage-secret=sb_secret_testing"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(JwtTestConfiguration.class)
class AssetPostgresTest {
    @Autowired JdbcTemplate jdbc; @Autowired MockMvc mvc;@Autowired JsonMapper json;@Autowired JwtTestSupport tokens;
    @Autowired AssetWorker worker; @Autowired AssetStore store;
    @MockitoBean BehaviorSuggestionProvider suggestions;
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
        jdbc.update("DELETE FROM shelter.behavior_suggestions WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.photo_upload_requests WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.asset_submissions WHERE job_id IN (SELECT id FROM shelter.asset_jobs WHERE dog_id=?)",dog);
        jdbc.update("DELETE FROM shelter.asset_steps WHERE job_id IN (SELECT id FROM shelter.asset_jobs WHERE dog_id=?)",dog);
        jdbc.update("DELETE FROM shelter.asset_jobs WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.dog_behavior_evidence WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.dog_behavior_profiles WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.dog_observations WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.asset_photo_sources WHERE photo_id IN (SELECT id FROM shelter.dog_photos WHERE dog_id=?)",dog);
        jdbc.update("DELETE FROM shelter.asset_source_permissions WHERE shelter_id=?",shelter);
        jdbc.update("DELETE FROM shelter.dog_photos WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.dogs WHERE id=?",dog);
        jdbc.update("DELETE FROM shelter.shelter_memberships WHERE shelter_id=?",shelter);
        jdbc.update("DELETE FROM shelter.shelters WHERE id=?",shelter);
        jdbc.update("DELETE FROM shelter.app_users WHERE id IN (?,?)",operator,user);
    }
    @Test void selectedActionsUseTheHarnessOnlyAfterRigReviewAndPublishAfterShelterReview() throws Exception {
        UUID permission=permission(true);UUID job=importPhoto(permission);
        assertThat(importPhoto(permission)).isEqualTo(job);
        finish(job);
        var draft=read(subject,job,200);assertThat(draft.at("/data/status").asText()).isEqualTo("REVIEW");
        assertThat(draft.at("/data/steps").size()).isEqualTo(4);
        verify(provider,times(3)).submit(any(),any());
        verify(provider,never()).submit(eq(AssetAction.WALK),any());
        mvc.perform(get("/v1/dogs/"+dog+"/assets")).andExpect(status().isNotFound());
        postJson(subject,"/v1/shelter-admin/dogs/"+dog+"/assets/"+job+"/review",Map.of("decision","APPROVE"),200);
        var result=mvc.perform(get("/v1/dogs/"+dog+"/assets")).andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store")).andReturn();
        JsonNode published=json.readTree(result.getResponse().getContentAsString());
        assertThat(published.at("/data/animations").size()).isEqualTo(3);
        assertThat(published.at("/data/animations/WALK/frameCount").asInt()).isEqualTo(24);
        assertThat(published.at("/data/fallbackAction").asText()).isEqualTo("IDLE");
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
    @Test void shelterPhotosCanEnterWithoutCrawlingPermission() throws Exception {
        var body=new HashMap<>(permissionBody(true));body.put("sourceKind","SHELTER");body.put("crawlAllowed",false);
        UUID grant=UUID.fromString(postJson(opSubject,"/v1/operations/asset-permissions",body,201).at("/data/id").asText());
        UUID job=importPhoto(grant);tick();tick();
        assertThat(read(subject,job,200).at("/data/status").asText()).isEqualTo("RIG_REVIEW");
        tick();tick();verify(provider,times(1)).submit(any(),any());
    }
    @Test void confirmedObservationsChooseOnlyTheTopTwoAdditionalActions() throws Exception {
        behavior(Map.of("RUN",90,"BACK_OFF",70,"SIT",70,"SNIFF",60),"CONFIRMED");
        UUID job=importPhoto(permission(true));
        var plan=read(subject,job,200).at("/data/actionPlan").valueStream().map(JsonNode::asText).toList();
        assertThat(plan).containsExactly("BASE","IDLE","WALK","SIT","RUN","BACK_OFF");
        finish(job);verify(provider,times(3)).submit(any(),any());
        verify(provider,never()).submit(eq(AssetAction.RUN),any());verify(provider,never()).submit(eq(AssetAction.BACK_OFF),any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_submissions WHERE job_id=?",Integer.class,job)).isEqualTo(3);
    }
    @Test void aNewConfirmedRevisionCreatesANewPlanWithoutChangingTheOldJob() throws Exception {
        behavior(Map.of("RUN",90),"CONFIRMED");UUID grant=permission(true),first=importPhoto(grant);
        jdbc.update("UPDATE shelter.dog_behavior_profiles SET settings=CAST(? AS jsonb),revision=revision+1 WHERE dog_id=?",json.writeValueAsString(settings(Map.of("SNIFF",90))),dog);
        UUID second=importPhoto(grant);assertThat(second).isNotEqualTo(first);assertThat(importPhoto(grant)).isEqualTo(second);
        assertThat(read(subject,first,200).at("/data/actionPlan").valueStream().map(JsonNode::asText).toList()).contains("RUN").doesNotContain("SNIFF");
        assertThat(read(subject,second,200).at("/data/actionPlan").valueStream().map(JsonNode::asText).toList()).contains("SNIFF").doesNotContain("RUN");
    }
    @Test void draftSettingsAndRetractedEvidenceUseThreeCommonActions() throws Exception {
        behavior(Map.of("RUN",90),"DRAFT");UUID grant=permission(true),first=importPhoto(grant);
        assertThat(read(subject,first,200).at("/data/actionPlan").size()).isEqualTo(4);
        jdbc.update("UPDATE shelter.dog_behavior_profiles SET status='CONFIRMED',revision=revision+1 WHERE dog_id=?",dog);
        jdbc.update("UPDATE shelter.dog_observations SET status='RETRACTED' WHERE dog_id=?",dog);
        assertThat(importPhoto(grant)).isEqualTo(first);
    }
    @Test void rigReviewChecksMembershipRevisionAndUntrustedPathsBeforeProceeding() throws Exception {
        UUID job=importPhoto(permission(true));tick();tick();
        var state=read(subject,job,200).path("data");String path="/v1/shelter-admin/dogs/"+dog+"/assets/"+job+"/rig";
        mvc.perform(get(path)).andExpect(status().isUnauthorized());
        mvc.perform(get(path).header("Authorization",bearer(outsiderSubject))).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization",bearer(subject))).andExpect(status().isOk());
        var body=Map.of("expectedRevision",state.path("rigRevision").asInt(),"profile",state.path("rigProfile"));
        postJson(outsiderSubject,path+"/confirm",body,403);
        postJson(subject,path+"/confirm",Map.of("expectedRevision",999,"profile",state.path("rigProfile")),409);
        var invalid=json.readTree(state.path("rigProfile").toString().replace("base.png","../other.png"));
        postJson(subject,path+"/confirm",Map.of("expectedRevision",1,"profile",invalid),422);
        assertThat(read(subject,job,200).at("/data/status").asText()).isEqualTo("RIG_REVIEW");
        postJson(subject,path+"/confirm",body,200);postJson(subject,path+"/confirm",body,409);
        verify(provider,times(1)).submit(any(),any());
    }
    @Test void interruptedLocalRenderingResumesWithoutAnotherPaidSubmission() throws Exception {
        UUID job=importPhoto(permission(true));tick();tick();var state=read(subject,job,200).path("data");
        postJson(subject,"/v1/shelter-admin/dogs/"+dog+"/assets/"+job+"/rig/confirm",Map.of("expectedRevision",1,"profile",state.path("rigProfile")),200);
        tick();tick();jdbc.update("UPDATE shelter.asset_jobs SET next_run_at=now() WHERE id=?",job);
        var work=store.claim();assertThat(work).isNotNull();assertThat(store.reserveLocal(work)).isTrue();
        jdbc.update("UPDATE shelter.asset_jobs SET lease_until=now()-interval '1 second' WHERE id=?",job);tick();
        finish(job);
        assertThat(read(subject,job,200).at("/data/status").asText()).isEqualTo("REVIEW");
        verify(provider,times(3)).submit(any(),any());
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
        UUID job=importPhoto(permission(true));finish(job);
        postJson(subject,"/v1/shelter-admin/dogs/"+dog+"/assets/"+job+"/review",Map.of("decision","APPROVE"),200);
        when(storage.sign(anyList())).thenAnswer(c->{
            jdbc.update("UPDATE shelter.dog_photos SET rights_status='REVOKED' WHERE id=?",photo);
            Map<String,String> links=new HashMap<>();for(String k:c.<List<String>>getArgument(0))links.put(k,"https://assets.example.invalid/"+k);return links;
        });
        var response=mvc.perform(get("/v1/dogs/"+dog+"/assets")).andExpect(status().isConflict()).andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("assets.example.invalid","spritesheetUrl");
    }
    @Test void confirmedBallPlayReservesRunAndSniffAlongsideTheThreeCommonActions() throws Exception {
        behavior(Map.of("RUN",10,"SNIFF",10,"BACK_OFF",100),"CONFIRMED");
        jdbc.update("UPDATE shelter.dog_behavior_profiles SET settings=jsonb_set(settings,'{ballPlay,chaseEnabled}','true') WHERE dog_id=?",dog);
        var job=importPhoto(permission(true));
        assertThat(read(subject,job,200).at("/data/actionPlan").valueStream().map(JsonNode::asText).toList()).containsExactly("BASE","IDLE","WALK","SIT","RUN","SNIFF");
        mvc.perform(get("/v1/dogs/"+dog+"/behavior")).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.interactions.BALL_CHASE.phases[2].preferredActions[0]").value("SNIFF"));
    }
    @Test void aiSuggestionSavesAnEvidenceBackedDraftAndRetriesNeverChargeAgain() throws Exception {
        UUID evidence=observation("공을 따라 달렸어요.");
        when(suggestions.suggest(anyList())).thenAnswer(c->{noTransaction();return json.valueToTree(Map.of("traits",List.of(Map.of("code","BALL_CHASER","observationId",evidence,"quote","공을 따라 달렸어요."))));});
        var body=Map.of("clientRequestId",UUID.randomUUID(),"expectedRevision",0,"evidenceObservationIds",List.of(evidence));
        String path="/v1/shelter-admin/dogs/"+dog+"/behavior/suggestions";
        var result=postJson(subject,path,body,200);assertThat(result.at("/data/status").asText()).isEqualTo("COMPLETED");
        assertThat(result.at("/data/result/profile/status").asText()).isEqualTo("DRAFT");
        assertThat(result.at("/data/result/profile/source").asText()).isEqualTo("AI_SUGGESTED");
        assertThat(result.at("/data/result/profile/settings/ballPlay/chaseEnabled").asBoolean()).isTrue();
        postJson(subject,path,body,200);verify(suggestions,times(1)).suggest(anyList());
        postJson(outsiderSubject,path,body,403);
        mvc.perform(get("/v1/dogs/"+dog+"/behavior")).andExpect(jsonPath("$.data.basis").value("DEFAULT"));
        postJson(subject,"/v1/shelter-admin/dogs/"+dog+"/behavior/confirmation",Map.of("expectedRevision",1),200);
        assertThat(read(subject,importPhoto(permission(true)),200).at("/data/actionPlan").valueStream().map(JsonNode::asText).toList()).contains("SIT","RUN","SNIFF");
    }
    @Test void aiCompletionRechecksEvidenceAndRevisionAndDoesNotOverwriteShelterEdits() throws Exception {
        UUID evidence=observation("공을 따라 달렸어요.");
        when(suggestions.suggest(anyList())).thenAnswer(c->{jdbc.update("UPDATE shelter.dog_observations SET status='RETRACTED' WHERE id=?",evidence);return json.valueToTree(Map.of("traits",List.of()));});
        var body=Map.of("clientRequestId",UUID.randomUUID(),"expectedRevision",0,"evidenceObservationIds",List.of(evidence));
        postJson(subject,"/v1/shelter-admin/dogs/"+dog+"/behavior/suggestions",body,409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.dog_behavior_profiles WHERE dog_id=?",Integer.class,dog)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM shelter.behavior_suggestions WHERE dog_id=?",String.class,dog)).isEqualTo("FAILED");
        postJson(subject,"/v1/shelter-admin/dogs/"+dog+"/behavior/suggestions",body,200);verify(suggestions,times(1)).suggest(anyList());
    }
    @Test void uploadsUseImmutablePrivateKeysAndAutomaticallyEnterTheExistingQueue() throws Exception {
        var consent=new HashMap<>(permissionBody(true));consent.put("sourceKind","SHELTER");consent.put("crawlAllowed",false);
        UUID grant=UUID.fromString(postJson(opSubject,"/v1/operations/asset-permissions",consent,201).at("/data/id").asText());
        var metadata=Map.of("clientUploadId",UUID.randomUUID(),"permissionId",grant,"rightsConfirmed",true,"rightsNote","직접 등록한 가상 사진의 가공·전송 허가");
        doAnswer(c->{noTransaction();return null;}).when(storage).putPhoto(any(),anyString(),any());
        String path="/v1/shelter-admin/dogs/"+dog+"/photos";
        var a=upload(subject,path,metadata,sprite,200);var b=upload(subject,path,metadata,sprite,200);
        assertThat(a.at("/data/photoId")).isEqualTo(b.at("/data/photoId"));assertThat(a.at("/data/job/id")).isEqualTo(b.at("/data/job/id"));
        verify(storage,times(1)).putPhoto(eq(dog),matches(dog+"/uploads/.*\\.png"),any());
        upload(outsiderSubject,path,metadata,sprite,403);
        var altered=new HashMap<>(metadata);altered.put("rightsNote","다른 허가");upload(subject,path,altered,sprite,409);
        upload(subject,path,Map.of("clientUploadId",UUID.randomUUID(),"permissionId",grant,"rightsConfirmed",false,"rightsNote","허가 없음"),sprite,409);
        mvc.perform(get(path).header("Authorization",bearer(subject))).andExpect(status().isOk()).andExpect(jsonPath("$.data.length()").value(2));
    }
    @Test void concurrentSuggestionOnlyCallsAiOnceAndRejectsAnInterveningProfileEdit() throws Exception {
        UUID evidence=observation("공을 따라 달렸어요.");
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(suggestions.suggest(anyList())).thenAnswer(c->{noTransaction();entered.countDown();assertThat(release.await(10,TimeUnit.SECONDS)).isTrue();return json.valueToTree(Map.of("traits",List.of()));});
        var body=Map.of("clientRequestId",UUID.randomUUID(),"expectedRevision",0,"evidenceObservationIds",List.of(evidence));
        String path="/v1/shelter-admin/dogs/"+dog+"/behavior/suggestions";
        try(var pool=Executors.newSingleThreadExecutor()) {
            var first=pool.submit(()->postJson(subject,path,body,409));
            try {
                assertThat(entered.await(10,TimeUnit.SECONDS)).isTrue();
                assertThat(postJson(subject,path,body,200).at("/data/status").asText()).isEqualTo("PENDING");
                behavior(Map.of("RUN",20),"DRAFT");
            } finally { release.countDown(); }
            first.get(10,TimeUnit.SECONDS);
        }
        verify(suggestions,times(1)).suggest(anyList());
        assertThat(jdbc.queryForObject("SELECT revision FROM shelter.dog_behavior_profiles WHERE dog_id=?",Integer.class,dog)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT status FROM shelter.behavior_suggestions WHERE dog_id=?",String.class,dog)).isEqualTo("FAILED");
    }
    @Test void failedPhotoUploadCanResumeButRevokedPermissionCannotPublishIt() throws Exception {
        var consent=new HashMap<>(permissionBody(true));consent.put("sourceKind","SHELTER");consent.put("crawlAllowed",false);
        UUID grant=UUID.fromString(postJson(opSubject,"/v1/operations/asset-permissions",consent,201).at("/data/id").asText());
        var metadata=Map.of("clientUploadId",UUID.randomUUID(),"permissionId",grant,"rightsConfirmed",true,"rightsNote","가상 허가");
        String path="/v1/shelter-admin/dogs/"+dog+"/photos";
        doThrow(new RuntimeException("storage unavailable")).doAnswer(c->{
            noTransaction();jdbc.update("UPDATE shelter.asset_source_permissions SET revoked_at=now() WHERE id=?",grant);return null;
        }).when(storage).putPhoto(any(),anyString(),any());
        upload(subject,path,metadata,sprite,500);upload(subject,path,metadata,sprite,409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.photo_upload_requests WHERE dog_id=?",Integer.class,dog)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT p.rights_status FROM shelter.dog_photos p JOIN shelter.photo_upload_requests u ON u.photo_id=p.id WHERE u.dog_id=?",String.class,dog)).isEqualTo("UNKNOWN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_jobs WHERE dog_id=?",Integer.class,dog)).isZero();
    }
    private JsonNode upload(UUID who,String path,Object metadata,byte[] png,int code) throws Exception {
        var response=mvc.perform(multipart(path).file(new MockMultipartFile("file","sample.png","image/png",png))
            .file(new MockMultipartFile("metadata","","application/json",json.writeValueAsBytes(metadata))).header("Authorization",bearer(who)))
            .andExpect(status().is(code)).andReturn().getResponse().getContentAsString();return json.readTree(response);
    }
    private UUID observation(String content) {
        UUID id=UUID.randomUUID();jdbc.update("INSERT INTO shelter.dog_observations(id,dog_id,category,content,observed_at,recorded_by,status,confirmed_by,confirmed_at) VALUES (?,?,'PLAY',?,now(),?,'CONFIRMED',?,now())",id,dog,content,user,user);return id;
    }
    private void finish(UUID job) throws Exception {
        for(int i=0;i<20;i++) {
            var state=read(subject,job,200).path("data");
            if(state.path("status").asText().equals("REVIEW")) return;
            if(state.path("status").asText().equals("RIG_REVIEW")) {
                postJson(subject,"/v1/shelter-admin/dogs/"+dog+"/assets/"+job+"/rig/confirm",
                    Map.of("expectedRevision",state.path("rigRevision").asInt(),"profile",state.path("rigProfile")),200);
            }
            tick();
        }
        fail("Generation did not reach review: "+read(subject,job,200));
    }
    private Map<String,Object> settings(Map<String,Integer> weights) {
        Map<String,Object> actions=new LinkedHashMap<>();
        for(AssetAction action:AssetAction.values()) if(action!=AssetAction.BASE) {
            double speed=switch(action) { case WALK->0.8;case RUN->1.8;case BACK_OFF->0.6;default->0; };
            actions.put(action.name(),Map.of("weight",weights.getOrDefault(action.name(),action==AssetAction.IDLE||action==AssetAction.WALK?10:0),
                "speedTilesPerSecond",speed,"minDurationMs",1000,"maxDurationMs",2000,"cooldownMs",500));
        }
        return Map.of("actions",actions,"approachDistanceTiles",3,"personalSpaceTiles",1,"reactionDelayMs",100,
            "ballPlay",Map.of("chaseEnabled",false,"returnEnabled",false,"reactionDelayMs",100));
    }
    private void behavior(Map<String,Integer> weights,String status) {
        UUID observation=UUID.randomUUID();
        jdbc.update("INSERT INTO shelter.dog_observations(id,dog_id,category,content,observed_at,recorded_by,status,confirmed_by,confirmed_at) VALUES (?,?,'PLAY','가상 관찰',now(),?,'CONFIRMED',?,now())",observation,dog,user,user);
        jdbc.update("INSERT INTO shelter.dog_behavior_profiles(dog_id,settings,status,confirmed_by,confirmed_at) VALUES (?,CAST(? AS jsonb),?,?,now())",dog,json.writeValueAsString(settings(weights)),status,user);
        jdbc.update("INSERT INTO shelter.dog_behavior_evidence(dog_id,observation_id) VALUES (?,?)",dog,observation);
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
