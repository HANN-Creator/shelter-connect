package org.shelterconnect.api;

import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.*;
import org.shelterconnect.api.asset.*;
import org.shelterconnect.api.auth.*;
import org.shelterconnect.api.behavior.*;
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

@Tag("postgres") @SpringBootTest(properties={"app.ai.enabled=true","app.ai.api-key=test-key", "app.assets.enabled=true","app.assets.api-key=test-key","app.assets.storage-secret=sb_secret_testing"})
@AutoConfigureMockMvc @ActiveProfiles("test") @Import(JwtTestConfiguration.class)
class StyledAssetPostgresTest {
    @Autowired JdbcTemplate jdbc;@Autowired MockMvc mvc;@Autowired JsonMapper json;@Autowired JwtTestSupport tokens;
    @Autowired StyledAssetWorker worker;@Autowired StyledAssetStore store;
    @Autowired StyledLessonWorker lessonWorker;@Autowired StyledLessonStore lessonStore;
    @Autowired StyledLearningRecoveryWorker recoveryWorker;
    @Autowired StyledLessonPromptComposer promptComposer;
    @MockitoBean StyledLessonAgent lessonAgent;
    @MockitoBean StyledLessonPromptAgent promptAgent;
    @MockitoBean StyledAssetProvider provider;@MockitoBean AssetStorage storage;
    @MockitoBean StyledSpriteCodec codec;@MockitoBean BehaviorSuggestionProvider suggestions;
    @MockitoBean StyledQualityAgent quality;
    @MockitoBean StyledSeedQualityAgent seedQuality;
    @MockitoBean org.shelterconnect.api.chat.OpenAiResponsesClient eyeAi;
    UUID op,user,opSubject,subject,shelter,dog,photo,permission;byte[] png;
    Map<String,byte[]> objects=new ConcurrentHashMap<>();
    List<UUID> extraDogs=new ArrayList<>();
    @BeforeAll static void migrate() throws Exception { SchemaMigrationTest.migratePostgres(); }
    @BeforeEach void setup() throws Exception {
        op=UUID.randomUUID();user=UUID.randomUUID();opSubject=UUID.randomUUID();subject=UUID.randomUUID();shelter=UUID.randomUUID();dog=UUID.randomUUID();photo=UUID.randomUUID();
        jdbc.update("INSERT INTO shelter.app_users(id,display_name,role,auth_provider,auth_subject) VALUES (?,'운영자','OPERATOR',?,?),(?,'담당자','USER',?,?)",op,tokens.properties.providerKey(),opSubject.toString(),user,tokens.properties.providerKey(),subject.toString());
        jdbc.update("INSERT INTO shelter.shelters(id,name,region,approval_status,is_public,reviewed_by,reviewed_at) VALUES (?,'가상 보호소','가상','APPROVED',true,?,now())",shelter,op);
        jdbc.update("INSERT INTO shelter.shelter_memberships(user_id,shelter_id,role,status) VALUES (?,?,'MANAGER','ACTIVE')",user,shelter);
        jdbc.update("INSERT INTO shelter.dogs(id,shelter_id,name,avatar_key,is_public,adoption_status) VALUES (?,?,'샘플','sample',true,'AVAILABLE')",dog,shelter);
        jdbc.update("INSERT INTO shelter.dog_photos(id,dog_id,storage_bucket,storage_key,sort_order,rights_status,rights_note,rights_confirmed_by,rights_confirmed_at) VALUES (?,?,'dog-photos',?,0,'GRANTED','가상 허가',?,now())",photo,dog,dog+"/source.png",op);
        var image=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);for(int y=4;y<30;y++)for(int x=8;x<25;x++)image.setRGB(x,y,0xffa07845);
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);png=out.toByteArray();
        when(storage.photo(any(),anyString(),anyString())).thenAnswer(c->{outsideTransaction();return png;});
        when(storage.asset(anyString())).thenAnswer(c->{outsideTransaction();return objects.get(c.getArgument(0));});
        doAnswer(c->{outsideTransaction();objects.put(c.getArgument(0),c.getArgument(1));return null;}).when(storage).put(anyString(),any());
        when(storage.sign(anyList())).thenAnswer(c->{outsideTransaction();var result=new HashMap<String,String>();for(String key:c.<List<String>>getArgument(0))result.put(key,"https://assets.example.invalid/"+key);return result;});
        when(codec.character(any(),any(),any(),any())).thenAnswer(c->{outsideTransaction();var payload=json.createObjectNode().put("character",true);payload.set("quality",c.<JsonNode>getArgument(3));return payload;});
        when(codec.motion(any(),anyString(),anyString(),any())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("action",c.getArgument(1),"direction",c.getArgument(2)));});
        when(codec.motion(any(),anyString(),anyString(),any(),any())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("action",c.getArgument(1),"direction",c.getArgument(2),"quality",c.getArgument(4)));});
        when(seedQuality.review(any(),anyList())).thenAnswer(c->{outsideTransaction();return seedReport(c.getArgument(1),true);});
        when(quality.contract(any(),any())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("tailCarriage","LOW","version",StyledQualityAgent.VERSION));});
        when(quality.review(any(),anyList(),anyList(),anyString(),anyString())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("passed",true,"issues",List.of()));});
        when(provider.submit(anyBoolean(),any())).thenAnswer(c->{outsideTransaction();return UUID.randomUUID();});
        when(provider.poll(any(),eq(true))).thenAnswer(c->{outsideTransaction();String b=Base64.getEncoder().encodeToString(png);return json.valueToTree(Map.of("status","COMPLETED","directions",Map.of("south",b,"north",b,"west",b,"east",b)));});
        when(provider.poll(any(),eq(false))).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("status","COMPLETED","frames",Collections.nCopies(9,Base64.getEncoder().encodeToString(png))));});
        when(promptAgent.compose(any(),anyString(),anyInt())).thenAnswer(c->{outsideTransaction();
            return json.valueToTree(Map.of("prompt",String.join(" ",c.<JsonNode>getArgument(0).valueStream().map(n->n.path("prevention").asText()).distinct().toList())));});
        when(promptAgent.rewrite(any(),anyString(),anyInt(),any(),any())).thenAnswer(c->{outsideTransaction();
            return json.valueToTree(Map.of("prompt",String.join(" ",c.<JsonNode>getArgument(0).valueStream().map(n->n.path("prevention").asText()).distinct().toList())));});
        when(promptAgent.verify(any(),anyString(),any(),anyInt())).thenAnswer(c->{outsideTransaction();
            return json.valueToTree(Map.of("preserved",Collections.nCopies(c.<JsonNode>getArgument(0).size(),true),"compatibleWithBase",true,"noNewRequirements",true));});
        permission=UUID.fromString(post(opSubject,"/v1/operations/asset-permissions",Map.of("shelterId",shelter,"sourceKey","test-"+dog,"sourceKind","SHELTER","permissionNote","disposable fixture","crawlAllowed",false,"derivativesAllowed",true,"pixellabAllowed",true,"autoGenerate",false),201).at("/data/id").asText());
        post(opSubject,"/v1/operations/asset-imports",Map.of("photoId",photo,"permissionId",permission),200);
    }
    JsonNode seedReport(List<byte[]> images,boolean passed) {
        try {
            var hashes=new ArrayList<String>();
            for(byte[] image:images)hashes.add(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(image)));
            String binding=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(String.join("|",hashes).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            return json.valueToTree(Map.of("version",StyledSeedQualityAgent.VERSION,"passed",passed,"inputSha256",binding,
                "rulesSha256",java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("asset-styles/cozy32-v1/quality-rules.json")))),"issues",passed?List.of():List.of("EYE_READABILITY")));
        }catch(Exception e){throw new AssertionError(e);}
    }
    @Test void unreadableSeedsAreBoundedlyRegeneratedBeforeAnyMotion()throws Exception {
        var attempts=new java.util.concurrent.atomic.AtomicInteger();
        var generations=new java.util.concurrent.atomic.AtomicInteger();
        when(provider.poll(any(),eq(true))).thenAnswer(c->{outsideTransaction();
            if(generations.getAndIncrement()>0) {
                var changed=ImageIO.read(new ByteArrayInputStream(png));changed.setRGB(12,9,0xffcdbbab);
                var out=new ByteArrayOutputStream();ImageIO.write(changed,"png",out);png=out.toByteArray();
            }
            String b=Base64.getEncoder().encodeToString(png);return json.valueToTree(Map.of("status","COMPLETED","directions",Map.of("south",b,"north",b,"west",b,"east",b)));
        });
        when(seedQuality.review(any(),anyList())).thenAnswer(c->{outsideTransaction();return seedReport(c.getArgument(1),attempts.getAndIncrement()>0);});
        UUID id=request();tick();tick();
        assertThat(read(id).path("status").asText()).isEqualTo("RUNNING");
        verify(provider,never()).submit(eq(false),any());
        tick();tick();assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");
        verify(provider,times(2)).submit(eq(true),any());
        String history=jdbc.queryForObject("SELECT attempt_history::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='character'",String.class,id);
        assertThat(history).contains("EYE_READABILITY","providerJobId","hashes");
        assertThat(objects.keySet()).anyMatch(k->k.contains("directions/repair-1/south.png"));
        var payloads=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(provider,times(2)).submit(eq(true),payloads.capture());
        assertThat(payloads.getAllValues().get(1).at("/quality/attempt").asInt()).isEqualTo(1);
        review(id,true,"APPROVE",200);finish(id);review(id,false,"APPROVE",200);
    }
    @Test void seedFailureOrUncertainEyesCannotBeApprovedOrBypassed()throws Exception {
        when(seedQuality.review(any(),anyList())).thenAnswer(c->seedReport(c.getArgument(1),false));
        UUID id=request();for(int i=0;i<6;i++)tick();
        assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");
        assertThat(read(id).path("failureCode").asText()).isEqualTo("SEED_QUALITY_REVIEW_REQUIRED");
        review(id,true,"APPROVE",409);for(int i=0;i<3;i++)tick();
        verify(provider,times(3)).submit(eq(true),any());verify(provider,never()).submit(eq(false),any());
        jdbc.update("UPDATE shelter.asset_jobs SET status='QUEUED',seed_review=jsonb_build_object('hashes',(SELECT result->'hashes' FROM shelter.styled_asset_steps WHERE job_id=? AND label='character')) WHERE id=?",id,id);
        tick();assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");verify(provider,never()).submit(eq(false),any());
        review(id,true,"REJECT",200);
    }
    @Test void seedInspectionErrorNeverBuysAnotherCharacterAutomatically()throws Exception {
        when(seedQuality.review(any(),anyList())).thenThrow(new RuntimeException("simulated AI failure"));
        UUID id=request();tick();tick();tick();
        assertThat(read(id).path("status").asText()).isEqualTo("FAILED");
        verify(provider,times(1)).submit(eq(true),any());verify(provider,never()).submit(eq(false),any());
    }
    Map<String,Object> seedRecheckBody(UUID id)throws Exception {
        var body=new HashMap<String,Object>();body.put("note","Recheck the same stored four seeds after correcting the review rules");
        body.put("expectedSeedHashes",read(id).at("/steps/0/result/hashes"));body.put("expectedRulesSha256","0".repeat(64));
        jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=jsonb_set(quality_policy,'{rulesSha256}',to_jsonb(CAST(? AS text))) WHERE id=?","0".repeat(64),id);
        return body;
    }
    @Test void exhaustedSeedRecheckKeepsBytesBudgetAndRequiresFreshHumanApproval()throws Exception {
        when(seedQuality.review(any(),anyList())).thenAnswer(c->seedReport(c.getArgument(1),false));
        UUID id=request();for(int i=0;i<6;i++)tick();
        var before=new HashMap<>(objects);var body=seedRecheckBody(id);
        post(UUID.randomUUID(),path(id)+"/quality-recheck",body,403);
        post(subject,path(id)+"/quality-recheck",body,200);post(subject,path(id)+"/quality-recheck",body,200);
        when(seedQuality.review(any(),anyList())).thenAnswer(c->{outsideTransaction();return seedReport(c.getArgument(1),true);});
        clearInvocations(provider);tick();
        assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");
        assertThat(read(id).at("/steps/0/qualityReport/passed").asBoolean()).isTrue();
        assertThat(read(id).at("/steps/0/repairCount").asInt()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT attempt_history::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='character'",String.class,id)).contains("qualityRecheck","EYE_READABILITY");
        assertThat(objects).containsExactlyInAnyOrderEntriesOf(before);verifyNoInteractions(provider);
        post(subject,path(id)+"/quality-recheck",body,200);tick();verifyNoInteractions(provider);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_submissions WHERE job_id=?",Integer.class,id)).isEqualTo(3);
        review(id,true,"APPROVE",200);finish(id);verify(provider,times(12)).submit(eq(false),any());
    }
    @Test void failedSeedRecheckDoesNotSpendUnusedRepairBudget()throws Exception {
        UUID id=request();tick();tick();var body=seedRecheckBody(id);
        post(subject,path(id)+"/quality-recheck",body,200);
        when(seedQuality.review(any(),anyList())).thenAnswer(c->seedReport(c.getArgument(1),false));
        clearInvocations(provider);tick();tick();
        assertThat(read(id).path("failureCode").asText()).isEqualTo("SEED_QUALITY_REVIEW_REQUIRED");
        assertThat(read(id).at("/steps/0/repairCount").asInt()).isZero();
        review(id,true,"APPROVE",409);verifyNoInteractions(provider);
    }
    @Test void seedRecheckRefusesChangedStoredBytesBeforeCallingVision()throws Exception {
        UUID id=request();tick();tick();var body=seedRecheckBody(id);
        post(subject,path(id)+"/quality-recheck",body,200);
        objects.put(read(id).at("/steps/0/result/keys/south").asText(),new byte[]{1,2,3});
        clearInvocations(seedQuality,provider);tick();
        assertThat(read(id).path("failureCode").asText()).isEqualTo("STYLED_SEED_CHANGED");
        verifyNoInteractions(seedQuality,provider);
    }
    @AfterEach void cleanup() {
        var dogs=new ArrayList<>(extraDogs);dogs.add(dog);
        for(UUID target:dogs)cleanupDog(target);
        jdbc.update("DELETE FROM shelter.asset_source_permissions WHERE shelter_id=?",shelter);
        jdbc.update("DELETE FROM shelter.shelter_memberships WHERE shelter_id=?",shelter);jdbc.update("DELETE FROM shelter.shelters WHERE id=?",shelter);
        jdbc.update("DELETE FROM shelter.app_users WHERE id IN (?,?)",op,user);
    }
    void cleanupDog(UUID dog) {
        jdbc.update("DELETE FROM shelter.asset_submissions WHERE job_id IN (SELECT id FROM shelter.asset_jobs WHERE dog_id=?)",dog);
        jdbc.update("DELETE FROM shelter.styled_asset_steps WHERE job_id IN (SELECT id FROM shelter.asset_jobs WHERE dog_id=?)",dog);
        jdbc.update("DELETE FROM shelter.asset_jobs WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.asset_photo_sources WHERE photo_id IN (SELECT id FROM shelter.dog_photos WHERE dog_id=?)",dog);
        jdbc.update("DELETE FROM shelter.behavior_suggestions WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.dog_behavior_evidence WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.dog_behavior_profiles WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.dog_observations WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.dog_photos WHERE dog_id=?",dog);jdbc.update("DELETE FROM shelter.dogs WHERE id=?",dog);
    }
    @Test void allDirectionsPersistAndOnlyReviewedAssetsAreReusedWithoutGeneration() throws Exception {
        UUID id=request();assertThat(request()).isEqualTo(id);assertThat(read(id).path("steps").size()).isEqualTo(13);
        tick();tick();assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");
        tick();verify(provider,times(1)).submit(anyBoolean(),any());publicStatus(404);
        var seeds=get(subject,path(id)+"/preview",200).path("data");assertThat(seeds.path("directions").size()).isEqualTo(4);
        review(id,true,"APPROVE",200);finish(id);publicStatus(404);
        assertThat(read(id).path("status").asText()).isEqualTo("REVIEW");review(id,false,"APPROVE",200);
        var manifest=get(null,"/v1/dogs/"+dog+"/assets",200).path("data");
        assertThat(manifest.at("/mapDirections/LEFT/WALK/frames").size()).isEqualTo(9);
        assertThat(manifest.at("/mapDirections/UP/SIT/returnToIdle").asText()).isEqualTo("REVERSE_FRAMES");
        assertThat(manifest.at("/mapDirections/LEFT/BACK_OFF").isMissingNode()).isTrue();
        assertThat(manifest.path("availableActions").valueStream().map(JsonNode::asText)).containsExactly("IDLE","WALK","SIT");
        assertThat(manifest.at("/behavior/interactions/PERSON_GREETING/enabled").asBoolean()).isFalse();
        assertThat(manifest.at("/frameSize/width").asInt()).isEqualTo(32);
        assertThat(manifest.toString()).doesNotContain("sourcePhoto","reviewedBy","photoId","provider_job_id","test-key");
        assertThat(objects).hasSize(16);verify(provider,times(13)).submit(anyBoolean(),any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_asset_steps WHERE job_id=? AND provider_result IS NOT NULL",Integer.class,id)).isZero();
        for(int i=0;i<3;i++) {publicStatus(200);tick();}
        assertThat(request()).isEqualTo(id);verify(provider,times(13)).submit(anyBoolean(),any());
        var pngSheet=ImageIO.read(new ByteArrayInputStream(objects.get(dog+"/"+id+"/native-32/sheets/walk-east.png")));
        assertThat(pngSheet.getWidth()).isEqualTo(288);assertThat(pngSheet.getRGB(8,4)).isEqualTo(0xffa07845);
    }
    @Test void interruptedSubmissionStopsUntilOperatorReconcilesKnownProviderId() throws Exception {
        UUID id=request();jdbc.update("UPDATE shelter.styled_asset_steps SET status='SUBMITTING',submitted_at=now() WHERE job_id=? AND label='character'",id);
        tick();assertThat(read(id).path("status").asText()).isEqualTo("OUTCOME_UNKNOWN");tick();verifyNoInteractions(provider);
        post(subject,"/v1/operations/styled-asset-jobs/"+id+"/recover",Map.of("providerJobId",UUID.randomUUID()),403);
        post(opSubject,"/v1/operations/styled-asset-jobs/"+id+"/recover",Map.of(),400);
        post(opSubject,"/v1/operations/styled-asset-jobs/"+id+"/recover",Map.of("providerJobId",UUID.randomUUID()),200);
        tick();assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");verify(provider,never()).submit(anyBoolean(),any());
    }
    @Test void storageFailureResumesDatabaseCheckpointEvenWhenProviderDisappears() throws Exception {
        UUID id=request();tick();doThrow(new RuntimeException("storage unavailable")).when(storage).put(anyString(),any());tick();
        assertThat(read(id).path("status").asText()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT provider_result IS NOT NULL FROM shelter.styled_asset_steps WHERE job_id=? AND label='character'",Boolean.class,id)).isTrue();
        doAnswer(c->{objects.put(c.getArgument(0),c.getArgument(1));return null;}).when(storage).put(anyString(),any());
        post(opSubject,"/v1/operations/styled-asset-jobs/"+id+"/recover",Map.of(),200);
        when(provider.poll(any(),anyBoolean())).thenThrow(new RuntimeException("expired"));tick();
        assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");verify(provider,times(1)).submit(anyBoolean(),any());verify(provider,times(1)).poll(any(),anyBoolean());
    }
    @Test void concurrentRequestsShareOneJobAndConcurrentWorkersSubmitOnce() throws Exception {
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->store.request(subject,dog,json.valueToTree(input())).id());var b=pool.submit(()->store.request(subject,dog,json.valueToTree(input())).id());
            assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
            var t1=pool.submit(worker::tick);var t2=pool.submit(worker::tick);t1.get(10,TimeUnit.SECONDS);t2.get(10,TimeUnit.SECONDS);
        }
        verify(provider,times(1)).submit(anyBoolean(),any());
    }
    @Test void revokedPermissionCancelsQueuedWorkAndHidesPublishedAssets() throws Exception {
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);review(id,false,"APPROVE",200);publicStatus(200);
        mvc.perform(delete("/v1/operations/asset-permissions/"+permission).header("Authorization",bearer(opSubject))).andExpect(status().isNoContent());
        publicStatus(404);tick();assertThat(read(id).path("status").asText()).isEqualTo("CANCELLED");verify(provider,times(13)).submit(anyBoolean(),any());
    }
    @Test void wrongSeedApprovalAndForeignReadCannotStartAnimations() throws Exception {
        UUID id=request();tick();tick();
        post(subject,path(id)+"/seed-review",Map.of("decision","APPROVE","note","reviewed all four images and their identity","expectedSeedHashes",Map.of()),409);
        get(UUID.randomUUID(),path(id),403);tick();verify(provider,times(1)).submit(anyBoolean(),any());
    }
    @Test void oldDailyLimitDeferralResumesDespitePriorSubmissions() throws Exception {
        UUID id=request();
        jdbc.update("INSERT INTO shelter.asset_submissions(job_id,action) SELECT ?,'WALK' FROM generate_series(1,1000)",id);
        jdbc.update("UPDATE shelter.asset_jobs SET failure_code='DAILY_REQUEST_LIMIT',next_run_at=now()+interval '1 day' WHERE id=?",id);
        worker.tick();verify(provider,times(1)).submit(anyBoolean(),any());
        assertThat(read(id).path("failureCode").isNull()).isTrue();
        assertThat(read(id).at("/steps/0/status").asText()).isEqualTo("WAITING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_submissions WHERE job_id=?",Integer.class,id)).isEqualTo(1001);
    }
    @Test void ordinaryDelayAndExistingLeaseStillPreventDuplicateReservations() throws Exception {
        UUID id=request();jdbc.update("UPDATE shelter.asset_jobs SET next_run_at=now()+interval '1 day' WHERE id=?",id);
        assertThat(store.claim()).isNull();
        jdbc.update("UPDATE shelter.asset_jobs SET failure_code='DAILY_REQUEST_LIMIT',lease_token=?,lease_until=now()+interval '1 minute' WHERE id=?",UUID.randomUUID(),id);
        assertThat(store.claim()).isNull();
        jdbc.update("UPDATE shelter.asset_jobs SET lease_token=NULL,lease_until=NULL WHERE id=?",id);
        var work=store.claim();assertThat(work).isNotNull();
        assertThat(store.reserve(work,json.valueToTree(Map.of("character",true)))).isTrue();
        assertThat(store.reserve(work,json.valueToTree(Map.of("character",true)))).isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_submissions WHERE job_id=?",Integer.class,id)).isEqualTo(1);
        verifyNoInteractions(provider);
    }
    @Test void expiredLeaseCanResumeAcceptedProviderButNeverResubmit() throws Exception {
        UUID id=request();tick();jdbc.update("UPDATE shelter.asset_jobs SET lease_until=now()-interval '1 minute',lease_token=?,next_run_at=now() WHERE id=?",UUID.randomUUID(),id);
        tick();assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");verify(provider,times(1)).submit(anyBoolean(),any());
    }
    @Test void definiteSubmissionRejectionRequiresExplicitRetryAndUncertainPostCannotRetryBlindly() throws Exception {
        UUID id=request();when(provider.submit(anyBoolean(),any())).thenThrow(new AssetProvider.Failure("PIXELLAB_HTTP_429",false));tick();
        assertThat(read(id).path("status").asText()).isEqualTo("FAILED");tick();verify(provider,times(1)).submit(anyBoolean(),any());
        post(opSubject,"/v1/operations/styled-asset-jobs/"+id+"/recover",Map.of(),200);
        doThrow(new AssetProvider.Failure("PIXELLAB_CONNECTION",true)).when(provider).submit(anyBoolean(),any());tick();
        assertThat(read(id).path("status").asText()).isEqualTo("OUTCOME_UNKNOWN");post(opSubject,"/v1/operations/styled-asset-jobs/"+id+"/recover",Map.of(),400);
    }
    @Test void confirmedProviderFailureRequiresExplicitNewAttemptAndPreservesHistory() throws Exception {
        UUID id=request();tick();
        UUID first=jdbc.queryForObject("SELECT provider_job_id FROM shelter.styled_asset_steps WHERE job_id=? AND label='character'",UUID.class,id);
        when(provider.poll(any(),eq(true))).thenReturn(json.valueToTree(Map.of("status","FAILED")));
        tick();assertThat(read(id).path("status").asText()).isEqualTo("FAILED");
        post(opSubject,"/v1/operations/styled-asset-jobs/"+id+"/recover",Map.of(),200);
        String history=jdbc.queryForObject("SELECT attempt_history::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='character'",String.class,id);
        assertThat(history).contains(first.toString());
        tick();verify(provider,times(2)).submit(anyBoolean(),any());
        assertThat(jdbc.queryForObject("SELECT provider_job_id FROM shelter.styled_asset_steps WHERE job_id=? AND label='character'",UUID.class,id)).isNotEqualTo(first);
    }
    @Test void readOnlyRuntimeCanUseNewTableButClientsCannot() {
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('shelter_runtime','shelter.styled_asset_steps','INSERT')",Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT has_table_privilege('authenticated','shelter.styled_asset_steps','SELECT')",Boolean.class)).isFalse();
    }
    @Test void learnedRuleTablesArePrivateAndEvidenceIsAppendOnlyForRuntime() {
        for(String table:List.of("styled_quality_examples","styled_quality_lessons","styled_quality_lesson_events")) {
            for(String role:List.of("anon","authenticated"))assertThat(jdbc.queryForObject("SELECT has_table_privilege(?,?,'SELECT')",Boolean.class,role,"shelter."+table)).isFalse();
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('shelter_runtime',?,'INSERT')",Boolean.class,"shelter."+table)).isTrue();
            assertThat(jdbc.queryForObject("SELECT has_table_privilege('shelter_runtime',?,'DELETE')",Boolean.class,"shelter."+table)).isFalse();
        }
        for(String table:List.of("styled_quality_examples","styled_quality_lesson_events"))assertThat(jdbc.queryForObject("SELECT has_table_privilege('shelter_runtime',?,'UPDATE')",Boolean.class,"shelter."+table)).isFalse();
    }
    @Test void lunaDraftOnlyChangesGenerationAfterConfirmationAndReadsNeverCallAi() throws Exception {
        UUID evidence=UUID.randomUUID();String content="사람을 좋아하고 산책을 좋아해요. 누워서 쉬는 것도 좋아해요.";
        jdbc.update("INSERT INTO shelter.dog_observations(id,dog_id,category,content,observed_at,recorded_by,status,confirmed_by,confirmed_at) VALUES (?,?,'PEOPLE',?,now(),?,'CONFIRMED',?,now())",evidence,dog,content,user,user);
        when(suggestions.suggest(anyList())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("traits",
            List.of("FRIENDLY","WALK_LOVER","RESTFUL").stream().map(code->Map.of("code",code,"observationId",evidence,"quote",content)).toList()));});
        var body=Map.of("clientRequestId",UUID.randomUUID(),"expectedRevision",0,"evidenceObservationIds",List.of(evidence));
        String behavior="/v1/shelter-admin/dogs/"+dog+"/behavior";
        var draft=post(subject,behavior+"/suggestions",body,200).at("/data/result");
        assertThat(draft.at("/generationPlan/expectedProviderRequests").asInt()).isEqualTo(29);
        assertThat(draft.at("/generationPlan/interactions/PERSON_GREETING/enabled").asBoolean()).isTrue();
        post(subject,behavior+"/suggestions",body,200);verify(suggestions,times(1)).suggest(anyList());
        UUID basic=request();assertThat(read(basic).path("steps").size()).isEqualTo(13);
        // Complete the original basic pack so newer behavior can also exercise clip availability filtering.
        tick();tick();review(basic,true,"APPROVE",200);finish(basic);review(basic,false,"APPROVE",200);
        post(subject,behavior+"/confirmation",Map.of("expectedRevision",1),200);
        var oldManifest=get(null,"/v1/dogs/"+dog+"/assets",200).path("data");
        assertThat(oldManifest.at("/behavior/settings/actions/RUN/weight").asInt()).isZero();
        assertThat(oldManifest.at("/behavior/interactions/PERSON_GREETING/enabled").asBoolean()).isFalse();
        UUID selected=request();assertThat(selected).isNotEqualTo(basic);assertThat(request()).isEqualTo(selected);
        assertThat(read(selected).path("steps").size()).isEqualTo(29);
        assertThat(read(selected).path("actionPlan").valueStream().map(JsonNode::asText)).containsExactly("BASE","IDLE","WALK","SIT","RUN","SNIFF","TAIL_WAG","LIE_DOWN");
        tick();tick();review(selected,true,"APPROVE",200);finish(selected);review(selected,false,"APPROVE",200);
        var manifest=get(null,"/v1/dogs/"+dog+"/assets",200).path("data");
        assertThat(manifest.at("/behavior/interactions/PERSON_GREETING/enabled").asBoolean()).isTrue();
        assertThat(manifest.at("/generationPlan/settings").isMissingNode()).isTrue();
        assertThat(manifest.at("/mapDirections/UP/LIE_DOWN/frameCount").asInt()).isEqualTo(9);
        assertThat(manifest.toString()).doesNotContain(evidence.toString(),content);
        jdbc.update("UPDATE shelter.dog_observations SET status='DRAFT',confirmed_by=NULL,confirmed_at=NULL WHERE id=?",evidence);
        var revoked=get(null,"/v1/dogs/"+dog+"/assets",200).path("data");
        assertThat(revoked.at("/behavior/basis").asText()).isEqualTo("DEFAULT");
        assertThat(revoked.at("/behavior/interactions/PERSON_GREETING/enabled").asBoolean()).isFalse();
        verify(suggestions,times(1)).suggest(anyList());verify(provider,times(42)).submit(anyBoolean(),any());
    }
    @Test void oldFullPacksWithoutBehaviorSnapshotStillResumeAndPublishAllDirections() throws Exception {
        UUID id=request();var all=List.of("IDLE","WALK","RUN","SNIFF","TAIL_WAG","BACK_OFF","SIT","LIE_DOWN");
        var plan=new ArrayList<String>();plan.add("BASE");plan.addAll(all);
        var canonical=new TreeMap<String,Object>();json.valueToTree(input()).path("traits").properties()
            .forEach(e->{if(!e.getKey().equals("reviewNote"))canonical.put(e.getKey(),e.getValue());});
        String oldKey=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(canonical)));
        jdbc.update("UPDATE shelter.asset_jobs SET behavior_plan=NULL,action_plan=?::jsonb,selection_key=? WHERE id=?",json.writeValueAsString(plan),oldKey,id);
        jdbc.update("DELETE FROM shelter.styled_asset_steps WHERE job_id=? AND ordinal>0",id);int ordinal=1;
        for(String action:all)for(String direction:List.of("south","north","west","east"))
            jdbc.update("INSERT INTO shelter.styled_asset_steps(job_id,ordinal,label,action,direction) VALUES (?,?,?,?,?)",id,ordinal++,action.toLowerCase(Locale.ROOT)+"-"+direction,action,direction);
        tick();tick();review(id,true,"APPROVE",200);finish(id);review(id,false,"APPROVE",200);
        var manifest=get(null,"/v1/dogs/"+dog+"/assets",200).path("data");
        assertThat(manifest.path("availableActions").size()).isEqualTo(8);
        assertThat(manifest.at("/mapDirections/LEFT/BACK_OFF/worldMotion/unitVector/x").asInt()).isEqualTo(1);
        assertThat(request()).isEqualTo(id);assertThat(objects).hasSize(36);verify(provider,times(33)).submit(anyBoolean(),any());
    }
    @Test void missingPlannedDirectionCannotReachReviewOrPublish() throws Exception {
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);
        jdbc.update("DELETE FROM shelter.styled_asset_steps WHERE job_id=? AND label='walk-north'",id);
        for(int i=0;i<26;i++)tick();
        assertThat(read(id).path("status").asText()).isEqualTo("FAILED");
        assertThat(read(id).path("failureCode").asText()).isEqualTo("ACTION_PLAN_INCOMPLETE");
        review(id,false,"APPROVE",409);publicStatus(404);
    }
    @Test void qualityFailureRegeneratesOnlyDefectiveClipAndRetainsPriorReceipt() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var generated=new java.util.concurrent.atomic.AtomicInteger();
        when(provider.poll(any(),eq(false))).thenAnswer(c->{
            var changed=ImageIO.read(new ByteArrayInputStream(png));changed.setRGB(12,12,0xffa07800|generated.incrementAndGet());
            var out=new ByteArrayOutputStream();ImageIO.write(changed,"png",out);
            var frames=new ArrayList<>(Collections.nCopies(9,Base64.getEncoder().encodeToString(out.toByteArray())));frames.set(0,Base64.getEncoder().encodeToString(png));
            return json.valueToTree(Map.of("status","COMPLETED","frames",frames));
        });
        when(quality.review(any(),anyList(),anyList(),eq("WALK"),eq("north"))).thenAnswer(c->json.valueToTree(Map.of(
            "passed",calls.incrementAndGet()>1,"issues",calls.get()==1?List.of("DIRECTION_DRIFT"):List.of())));
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);
        assertThat(read(id).path("status").asText()).isEqualTo("REVIEW");
        verify(provider,times(14)).submit(anyBoolean(),any());verify(quality,times(1)).contract(any(),any());
        assertThat(jdbc.queryForObject("SELECT repair_count FROM shelter.styled_asset_steps WHERE job_id=? AND label='walk-north'",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_history::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='walk-north'",String.class,id)).contains("DIRECTION_DRIFT","providerJobId","sha256");
        var payloads=org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(provider,times(14)).submit(anyBoolean(),payloads.capture());
        var repairs=payloads.getAllValues().stream().filter(p->p.at("/quality/attempt").asInt()>0).toList();
        assertThat(repairs).hasSize(1);
        assertThat(repairs.getFirst().at("/quality/issues").toString()).isEqualTo("[\"DIRECTION_DRIFT\"]");
        assertThat(repairs.getFirst().at("/quality/rulesSha256").asText()).matches("[a-f0-9]{64}");
        assertThat(read(id).at("/qualityPolicy/rulesSha256").asText()).isEqualTo(repairs.getFirst().at("/quality/rulesSha256").asText());
        review(id,false,"APPROVE",200);publicStatus(200);
    }
    @Test void excessiveIdleMotionUsesBoundedRepairAndRetainsTheOriginalVerdict() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        var generated=new java.util.concurrent.atomic.AtomicInteger();
        when(provider.poll(any(),eq(false))).thenAnswer(c->{
            var changed=ImageIO.read(new ByteArrayInputStream(png));changed.setRGB(12,12,0xffa07800|generated.incrementAndGet());
            var out=new ByteArrayOutputStream();ImageIO.write(changed,"png",out);
            var frames=new ArrayList<>(Collections.nCopies(9,Base64.getEncoder().encodeToString(out.toByteArray())));frames.set(0,Base64.getEncoder().encodeToString(png));
            return json.valueToTree(Map.of("status","COMPLETED","frames",frames));
        });
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("west"))).thenAnswer(c->json.valueToTree(Map.of(
            "passed",calls.incrementAndGet()>1,"issues",calls.get()==1?List.of("IDLE_MOTION"):List.of())));
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);
        assertThat(read(id).path("status").asText()).isEqualTo("REVIEW");
        verify(provider,times(14)).submit(anyBoolean(),any());verify(quality,times(1)).contract(any(),any());
        assertThat(jdbc.queryForObject("SELECT repair_count FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-west'",Integer.class,id)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT attempt_history::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-west'",String.class,id)).contains("IDLE_MOTION","providerJobId","sha256");
        var payloads=org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(provider,times(14)).submit(anyBoolean(),payloads.capture());
        var repairs=payloads.getAllValues().stream().filter(p->p.at("/quality/attempt").asInt()>0).toList();
        assertThat(repairs).hasSize(1);
        assertThat(repairs.getFirst().at("/quality/issues").toString()).isEqualTo("[\"IDLE_MOTION\"]");
        assertThat(repairs.getFirst().at("/quality/rulesSha256").asText()).matches("[a-f0-9]{64}");
        assertThat(read(id).at("/qualityPolicy/rulesSha256").asText()).isEqualTo(repairs.getFirst().at("/quality/rulesSha256").asText());
        review(id,false,"APPROVE",200);publicStatus(200);
    }
    @Test void aChangedPinnedPolicyStopsBeforeAnyPaidOrVisionCall() throws Exception {
        UUID id=request();
        jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=jsonb_set(quality_policy,'{rulesSha256}',to_jsonb(CAST(? AS text))) WHERE id=?","0".repeat(64),id);
        tick();assertThat(read(id).path("failureCode").asText()).isEqualTo("QUALITY_RULES_CHANGED");
        verifyNoInteractions(provider,quality);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_submissions WHERE job_id=?",Integer.class,id)).isZero();
    }
    @Test void exhaustedRepairBudgetBlocksApprovalAndReplayCannotBuyMoreAttempts() throws Exception {
        when(quality.review(any(),anyList(),anyList(),eq("SIT"),eq("north"))).thenReturn(json.valueToTree(Map.of("passed",false,"issues",List.of("DIRECTION_DRIFT"))));
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);
        assertThat(read(id).path("failureCode").asText()).isEqualTo("QUALITY_REPAIR_EXHAUSTED");
        verify(provider,times(15)).submit(anyBoolean(),any());review(id,false,"APPROVE",409);publicStatus(404);
        var body=Map.of("note","Run the bounded quality repair without resetting paid attempts","expectedSeedHashes",read(id).at("/steps/0/result/hashes"));
        post(subject,path(id)+"/repair",body,200);assertThat(request()).isEqualTo(id);tick();
        verify(provider,times(15)).submit(anyBoolean(),any());
    }
    @Test void legacyReviewPackAuditsAndReusesGoodClipsWithoutAnyNewPixelLabCall() throws Exception {
        UUID id=request();jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=NULL WHERE id=?",id);
        tick();tick();review(id,true,"APPROVE",200);finish(id);var before=new HashMap<>(objects);
        var body=Map.of("note","Audit this old pack and automatically repair only defective clips","expectedSeedHashes",read(id).at("/steps/0/result/hashes"));
        post(UUID.randomUUID(),path(id)+"/repair",body,403);
        post(subject,path(id)+"/repair",body,200);post(subject,path(id)+"/repair",body,200);finish(id);
        verify(provider,times(13)).submit(anyBoolean(),any());verify(quality,times(12)).review(any(),anyList(),anyList(),anyString(),anyString());
        for(var e:before.entrySet())assertThat(objects.get(e.getKey())).isEqualTo(e.getValue());
        review(id,false,"APPROVE",200);
    }
    @Test void interruptedAiReviewRequiresRecoveryAndDoesNotResubmitPixelLab() throws Exception {
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);tick(); // shared tail contract
        tick(); // first clip accepted
        when(quality.review(any(),anyList(),anyList(),anyString(),anyString())).thenThrow(new AssetProvider.Failure("QUALITY_AI_TIMEOUT",false));
        tick();assertThat(read(id).path("failureCode").asText()).isEqualTo("QUALITY_AI_TIMEOUT");tick();verify(provider,times(2)).submit(anyBoolean(),any());
        doReturn(json.valueToTree(Map.of("passed",true,"issues",List.of()))).when(quality).review(any(),anyList(),anyList(),anyString(),anyString());
        post(opSubject,"/v1/operations/styled-asset-jobs/"+id+"/recover",Map.of(),200);finish(id);
        verify(provider,times(13)).submit(anyBoolean(),any());review(id,false,"APPROVE",200);
    }
    @Test void changedRuleRecheckKeepsExhaustedBudgetHistoryAndReplayDoesNotBuyMore() throws Exception {
        when(quality.review(any(),anyList(),anyList(),eq("SIT"),eq("north"))).thenReturn(json.valueToTree(Map.of("passed",false,"issues",List.of("DIRECTION_DRIFT"))));
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);
        var body=new HashMap<String,Object>();
        body.put("note","Recheck stored images with the newly deployed quality rule");
        body.put("expectedSeedHashes",read(id).at("/steps/0/result/hashes"));
        body.put("expectedRulesSha256",read(id).at("/qualityPolicy/rulesSha256").asText());
        post(subject,path(id)+"/quality-recheck",body,409); // same rules cannot reset budget
        String old="0".repeat(64);
        jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=jsonb_set(quality_policy,'{rulesSha256}',to_jsonb(CAST(? AS text))) WHERE id=?",old,id);
        body.put("expectedRulesSha256",old);
        post(UUID.randomUUID(),path(id)+"/quality-recheck",body,403);
        body.put("expectedRulesSha256","1".repeat(64));post(subject,path(id)+"/quality-recheck",body,409);
        body.put("expectedRulesSha256",old);
        var result=post(subject,path(id)+"/quality-recheck",body,200).path("data");
        assertThat(result.path("status").asText()).isEqualTo("QUEUED");
        assertThat(result.at("/qualityPolicy/recheckFromRulesSha256").asText()).isEqualTo(old);
        post(subject,path(id)+"/quality-recheck",body,200);
        assertThat(jdbc.queryForObject("SELECT repair_count FROM shelter.styled_asset_steps WHERE job_id=? AND label='sit-north'",Integer.class,id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT attempt_history::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='sit-north'",String.class,id)).contains("qualityRecheck","DIRECTION_DRIFT");
        tick();assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");
        review(id,true,"APPROVE",200);finish(id);verify(provider,times(15)).submit(anyBoolean(),any());
        assertThat(read(id).path("failureCode").asText()).isEqualTo("QUALITY_REPAIR_EXHAUSTED");
        post(subject,path(id)+"/quality-recheck",body,200);tick();verify(provider,times(15)).submit(anyBoolean(),any());
        review(id,false,"APPROVE",409);
    }
    @Test void explicitRecheckNeverRegeneratesANewlyFailedClipWithUnusedRepairBudget()throws Exception {
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);
        var before=read(id).path("steps");var originalObjects=new HashMap<>(objects);
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("north")))
            .thenReturn(json.valueToTree(Map.of("passed",false,"issues",List.of("IDLE_MOTION"))));
        String old="0".repeat(64);
        jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=jsonb_set(quality_policy,'{rulesSha256}',to_jsonb(CAST(? AS text))) WHERE id=?",old,id);
        var body=Map.of("note","Inspect exact stored bytes after new evidence rules without regeneration",
            "expectedSeedHashes",read(id).at("/steps/0/result/hashes"),"expectedRulesSha256",old);
        post(subject,path(id)+"/quality-recheck",body,200);tick();review(id,true,"APPROVE",200);finish(id);
        var after=read(id);assertThat(after.path("failureCode").asText()).isEqualTo("QUALITY_REPAIR_EXHAUSTED");
        for(int i=0;i<before.size();i++) {
            assertThat(after.path("steps").get(i).path("result")).isEqualTo(before.get(i).path("result"));
            assertThat(after.path("steps").get(i).path("repairCount")).isEqualTo(before.get(i).path("repairCount"));
        }
        for(var e:originalObjects.entrySet())assertThat(objects.get(e.getKey())).isEqualTo(e.getValue());
        verify(provider,times(13)).submit(anyBoolean(),any());
        post(subject,path(id)+"/quality-recheck",body,200);tick();verify(provider,times(13)).submit(anyBoolean(),any());
        review(id,false,"APPROVE",409);
    }
    @Test void approvedPackCannotBeRecheckedWithChangedRules() throws Exception {
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);review(id,false,"APPROVE",200);
        String old="0".repeat(64);
        jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=jsonb_set(quality_policy,'{rulesSha256}',to_jsonb(CAST(? AS text))) WHERE id=?",old,id);
        post(subject,path(id)+"/quality-recheck",Map.of("note","Do not change already approved public outputs","expectedSeedHashes",read(id).at("/steps/0/result/hashes"),"expectedRulesSha256",old),409);
        assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
        verify(provider,times(13)).submit(anyBoolean(),any());
    }
    @Test void repeatedIdleFailureEditsAllFramesOnLastRepairIncludingUnknownTail() throws Exception {
        when(quality.contract(any(),any())).thenReturn(json.valueToTree(Map.of("tailCarriage","UNKNOWN")));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("south"))).thenAnswer(c->{
            boolean ok=calls.incrementAndGet()>2;
            return json.valueToTree(Map.of("passed",ok,"issues",ok?List.of():List.of("IDLE_MOTION")));
        });
        when(codec.seedIdle(anyString(),any(),anyInt())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("idleEdited",true));});
        when(provider.editAnimation(any())).thenAnswer(c->{outsideTransaction();return UUID.randomUUID();});
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);
        assertThat(read(id).path("failureCode").isNull()).isTrue();
        verify(provider,times(14)).submit(anyBoolean(),any());verify(provider,times(1)).editAnimation(any());
        var source=org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(codec).seedIdle(eq("south"),source.capture(),anyInt());
        assertThat(ImageIO.read(new ByteArrayInputStream(source.getValue())).getWidth()).isEqualTo(32);
        assertThat(jdbc.queryForObject("SELECT repair_count FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-south'",Integer.class,id)).isEqualTo(2);
        assertThat(calls.get()).isEqualTo(4); // Original + regenerated + restored edit + raw edit.
        var step=read(id).path("steps").get(1);
        assertThat(step.at("/result/rawEdit/sha256").asText()).matches("[a-f0-9]{64}");
        assertThat(step.at("/qualityReport/rawEditReview/passed").asBoolean()).isTrue();
        review(id,false,"APPROVE",200);publicStatus(200);
    }
    @Test void failedRawIdleEditBlocksApprovalWithoutAnyThirdRepairOrHiddenFrame() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("north"))).thenAnswer(c->{
            boolean ok=calls.incrementAndGet()==3;
            return json.valueToTree(Map.of("passed",ok,"issues",ok?List.of():List.of("IDLE_MOTION")));
        });
        when(codec.seedIdle(anyString(),any(),anyInt())).thenReturn(json.valueToTree(Map.of("idleEdited",true)));
        when(provider.editAnimation(any())).thenReturn(UUID.randomUUID());
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);
        assertThat(read(id).path("failureCode").asText()).isEqualTo("QUALITY_REPAIR_EXHAUSTED");
        review(id,false,"APPROVE",409);publicStatus(404);
        post(subject,path(id)+"/repair",Map.of("note","Recheck must preserve the exhausted budget and failed raw edit","expectedSeedHashes",read(id).at("/steps/0/result/hashes")),200);tick();
        verify(provider,times(14)).submit(anyBoolean(),any());verify(provider,times(1)).editAnimation(any());
        assertThat(jdbc.queryForObject("SELECT repair_count FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-north'",Integer.class,id)).isEqualTo(2);
    }
    @Test void existingIdleJobsKeepTheirPinnedRegenerationPolicyAfterDeployment() throws Exception {
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("south"))).thenReturn(json.valueToTree(Map.of("passed",false,"issues",List.of("IDLE_MOTION"))));
        UUID id=request();jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=quality_policy-'idleRepair' WHERE id=?",id);
        tick();tick();review(id,true,"APPROVE",200);finish(id);
        verify(provider,times(15)).submit(anyBoolean(),any());verify(provider,never()).editAnimation(any());
        assertThat(read(id).path("failureCode").asText()).isEqualTo("QUALITY_REPAIR_EXHAUSTED");
        review(id,false,"APPROVE",409);
    }
    @Test void repeatedWalkClippingUsesOneCompleteEditWithinExistingBudget() throws Exception {
        when(quality.contract(any(),any())).thenReturn(json.valueToTree(Map.of("tailCarriage","UNKNOWN")));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(quality.review(any(),anyList(),anyList(),eq("WALK"),eq("west"))).thenAnswer(c->{
            boolean ok=calls.incrementAndGet()>2;
            return json.valueToTree(Map.of("passed",ok,"issues",ok?List.of():List.of("CANVAS_CLIPPING")));
        });
        when(codec.marginEdit(anyString(),anyString(),any(),anyInt())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("marginEdited",true));});
        when(provider.editAnimation(any())).thenAnswer(c->{outsideTransaction();return UUID.randomUUID();});
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);
        assertThat(read(id).at("/qualityPolicy/marginRepair").asText()).isEqualTo(StyledSpriteCodec.MARGIN_EDIT_VERSION);
        verify(provider,times(14)).submit(anyBoolean(),any());verify(provider,times(1)).editAnimation(any());
        var source=org.mockito.ArgumentCaptor.forClass(byte[].class);
        verify(codec).marginEdit(eq("WALK"),eq("west"),source.capture(),anyInt());
        assertThat(ImageIO.read(new ByteArrayInputStream(source.getValue())).getWidth()).isEqualTo(288);
        assertThat(calls.get()).isEqualTo(4);
        var step=read(id).path("steps").valueStream().filter(n->n.path("label").asText().equals("walk-west")).findFirst().orElseThrow();
        assertThat(step.path("repairCount").asInt()).isEqualTo(2);
        assertThat(step.at("/qualityReport/rawEditReview/passed").asBoolean()).isTrue();
        review(id,false,"APPROVE",200);
    }
    @Test void rawMarginEditFailureStillBlocksApprovalAfterRestoredResultPasses() throws Exception {
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(quality.review(any(),anyList(),anyList(),eq("SIT"),eq("west"))).thenAnswer(c->{
            boolean ok=calls.incrementAndGet()==3;
            return json.valueToTree(Map.of("passed",ok,"issues",ok?List.of():List.of("CANVAS_CLIPPING")));
        });
        when(codec.marginEdit(anyString(),anyString(),any(),anyInt())).thenReturn(json.valueToTree(Map.of("marginEdited",true)));
        when(provider.editAnimation(any())).thenReturn(UUID.randomUUID());
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);
        assertThat(read(id).path("failureCode").asText()).isEqualTo("QUALITY_REPAIR_EXHAUSTED");
        var step=read(id).path("steps").valueStream().filter(n->n.path("label").asText().equals("sit-west")).findFirst().orElseThrow();
        assertThat(step.at("/qualityReport/rawEditReview/passed").asBoolean()).isFalse();
        assertThat(step.path("repairCount").asInt()).isEqualTo(2);
        review(id,false,"APPROVE",409);publicStatus(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=? AND label='sit-west' AND passed",Integer.class,id)).isZero();
        post(subject,path(id)+"/repair",Map.of("note","Do not reset the margin repair budget after the raw edit failed","expectedSeedHashes",read(id).at("/steps/0/result/hashes")),200);tick();
        verify(provider,times(14)).submit(anyBoolean(),any());verify(provider,times(1)).editAnimation(any());
    }
    @Test void existingMotionJobsRetainRegenerationWhenMarginPolicyIsAbsent() throws Exception {
        when(quality.review(any(),anyList(),anyList(),eq("WALK"),eq("west"))).thenReturn(json.valueToTree(Map.of("passed",false,"issues",List.of("CANVAS_CLIPPING"))));
        UUID id=request();jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=quality_policy-'marginRepair' WHERE id=?",id);
        tick();tick();review(id,true,"APPROVE",200);finish(id);
        verify(provider,times(15)).submit(anyBoolean(),any());verify(provider,never()).editAnimation(any());
        assertThat(read(id).path("failureCode").asText()).isEqualTo("QUALITY_REPAIR_EXHAUSTED");
        review(id,false,"APPROVE",409);
    }
    UUID continuationFixture(String label) throws Exception {
        // Symmetric coat/shape fixture; the ordinary failure remains archived.
        var image=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
        for(int y=4;y<30;y++)for(int x=8;x<24;x++)image.setRGB(x,y,0xffa07845);
        var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);png=out.toByteArray();
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);
        jdbc.update("UPDATE shelter.styled_asset_steps SET repair_count=2,quality_report=quality_report || '{\"passed\":false,\"issues\":[\"IDLE_MOTION\",\"CANVAS_CLIPPING\"]}'::jsonb WHERE job_id=? AND label=?",id,label);
        jdbc.update("UPDATE shelter.styled_asset_steps s SET quality_report=quality_report || jsonb_build_object('rulesSha256',j.quality_policy->>'rulesSha256') FROM shelter.asset_jobs j WHERE s.job_id=j.id AND j.id=?",id);
        return id;
    }
    Map<String,Object> continuationBody(UUID id,String label) throws Exception {
        var j=read(id);var step=j.path("steps").valueStream().filter(n->n.path("label").asText().equals(label)).findFirst().orElseThrow();
        return new HashMap<>(Map.of("requestId",UUID.randomUUID(),"note","Explicitly continue one failed motion while retaining every prior attempt",
            "expectedSeedHashes",j.at("/steps/0/result/hashes"),"expectedSheetHashes",Map.of(label,step.at("/result/sha256").asText())));
    }
    @Test void explicitContinuationMirrorsOnlyBoundPassingSourceAndReplaysWithoutBuying() throws Exception {
        UUID id=continuationFixture("walk-west");var before=read(id);var body=continuationBody(id,"walk-west");
        post(UUID.randomUUID(),path(id)+"/repair-continuation",body,403);
        post(subject,path(id)+"/repair-continuation",body,200);post(subject,path(id)+"/repair-continuation",body,200);
        clearInvocations(provider);finish(id);
        assertThat(read(id).path("failureCode").isNull()).isTrue();verifyNoInteractions(provider);
        var step=read(id).path("steps").valueStream().filter(n->n.path("label").asText().equals("walk-west")).findFirst().orElseThrow();
        assertThat(step.path("repairCount").asInt()).isEqualTo(3);
        assertThat(step.at("/result/derivation/strategy").asText()).isEqualTo(StyledSpriteCodec.MIRROR_VERSION);
        assertThat(step.at("/qualityReport/rawEditReview/passed").asBoolean()).isTrue();
        assertThat(read(id).path("seedReview")).isEqualTo(before.path("seedReview"));
        assertThat(jdbc.queryForObject("SELECT attempt_history::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='walk-west'",String.class,id)).contains("continuationRequestId","CANVAS_CLIPPING","repairCount");
        post(subject,path(id)+"/repair-continuation",body,200);tick();verifyNoInteractions(provider);
        body.put("requestId",UUID.randomUUID());post(subject,path(id)+"/repair-continuation",body,409);
        review(id,false,"APPROVE",200);
    }
    @Test void continuationRefusesStaleSourcePassingTargetOrUnapprovedSeed() throws Exception {
        UUID id=continuationFixture("walk-west");var body=continuationBody(id,"walk-west");var original=body.get("expectedSheetHashes");
        body.put("expectedSheetHashes",Map.of("walk-west","0".repeat(64)));post(subject,path(id)+"/repair-continuation",body,409);body.put("expectedSheetHashes",original);
        jdbc.update("UPDATE shelter.styled_asset_steps SET quality_report=quality_report || '{\"inputSha256\":\"stale\"}'::jsonb WHERE job_id=? AND label='walk-east'",id);
        post(subject,path(id)+"/repair-continuation",body,409);
        post(subject,path(id)+"/repair-continuation",continuationBody(id,"sit-east"),409);
        jdbc.update("UPDATE shelter.asset_jobs SET seed_review=NULL WHERE id=?",id);
        post(subject,path(id)+"/repair-continuation",body,409);
        assertThat(jdbc.queryForObject("SELECT repair_count FROM shelter.styled_asset_steps WHERE job_id=? AND label='walk-west'",Integer.class,id)).isEqualTo(2);
    }
    @Test void seedIdleContinuationIsOneNewPaidEditAndFailedRawCannotPublish() throws Exception {
        UUID id=continuationFixture("idle-north");var body=continuationBody(id,"idle-north");
        when(codec.seedIdle(anyString(),any(),anyInt())).thenReturn(json.valueToTree(Map.of("seedIdle",true)));
        when(provider.editAnimation(any())).thenReturn(UUID.randomUUID());
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("north"))).thenAnswer(c->json.valueToTree(Map.of("passed",calls.incrementAndGet()==1,"issues",List.of("IDLE_MOTION"))));
        clearInvocations(provider);post(subject,path(id)+"/repair-continuation",body,200);finish(id);
        verify(provider,times(1)).editAnimation(any());verify(provider,never()).submit(anyBoolean(),any());
        assertThat(read(id).path("failureCode").asText()).isEqualTo("QUALITY_REPAIR_EXHAUSTED");review(id,false,"APPROVE",409);
        post(subject,path(id)+"/repair-continuation",body,200);tick();verify(provider,times(1)).editAnimation(any());
        assertThat(jdbc.queryForObject("SELECT repair_count FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-north'",Integer.class,id)).isEqualTo(3);
    }
    @Test void continuationStopsBeforeQualityIfThePassingSourceBytesChange() throws Exception {
        UUID id=continuationFixture("sit-west");var body=continuationBody(id,"sit-west");
        post(subject,path(id)+"/repair-continuation",body,200);
        var source=read(id).path("steps").valueStream().filter(n->n.path("label").asText().equals("sit-east")).findFirst().orElseThrow();
        objects.put(source.at("/result/key").asText(),new byte[]{1});clearInvocations(provider,quality);tick();
        assertThat(read(id).path("failureCode").asText()).isEqualTo("STYLED_SHEET_CHANGED");verifyNoInteractions(provider,quality);
    }
    JsonNode step(UUID id,String label) throws Exception {
        return read(id).path("steps").valueStream().filter(n->n.path("label").asText().equals(label)).findFirst().orElseThrow();
    }
    Map<String,Object> legacyMirroredIdle(UUID id) throws Exception {
        var before=step(id,"idle-west");var original=continuationBody(id,"idle-west");
        post(subject,path(id)+"/repair-continuation",original,200);
        // Reconstruct the pre-B60 persisted mirror grant, without paying for a fixture image.
        jdbc.update("UPDATE shelter.asset_jobs SET status='REVIEW',quality_policy=jsonb_set(quality_policy,'{repairContinuation,plans,idle-west,strategy}',to_jsonb(CAST(? AS text))) WHERE id=?",StyledSpriteCodec.MIRROR_VERSION,id);
        jdbc.update("UPDATE shelter.styled_asset_steps SET status='SUCCEEDED',result=CAST(? AS jsonb) || jsonb_build_object('derivation',jsonb_build_object('strategy',CAST(? AS text))),quality_report=CAST(? AS jsonb) || '{\"passed\":false,\"issues\":[\"IDLE_MOTION\"]}'::jsonb WHERE job_id=? AND label='idle-west'",
            json.writeValueAsString(before.path("result")),StyledSpriteCodec.MIRROR_VERSION,json.writeValueAsString(before.path("qualityReport")),id);
        return original;
    }
    @Test void idleUsesOwnSeedEvenWithPassingOppositeAndSeparateClipsRetainOldIdempotency() throws Exception {
        UUID id=continuationFixture("idle-west");var original=continuationBody(id,"idle-west");
        when(codec.seedIdle(anyString(),any(),anyInt())).thenReturn(json.valueToTree(Map.of("seedIdle",true)));
        when(provider.editAnimation(any())).thenReturn(UUID.randomUUID());clearInvocations(provider);
        post(subject,path(id)+"/repair-continuation",original,200);finish(id);
        verify(codec).seedIdle(eq("west"),eq(png),anyInt());verify(provider,times(1)).editAnimation(any());
        assertThat(read(id).at("/qualityPolicy/repairContinuation/plans/idle-west/strategy").asText()).isEqualTo(StyledSpriteCodec.SEED_IDLE_VERSION);
        var kept=step(id,"idle-west");var seeds=read(id).path("seedReview");
        jdbc.update("UPDATE shelter.styled_asset_steps SET repair_count=2,quality_report=quality_report || '{\"passed\":false,\"issues\":[\"IDLE_MOTION\"]}'::jsonb WHERE job_id=? AND label='idle-east'",id);
        var next=continuationBody(id,"idle-east");post(subject,path(id)+"/repair-continuation",next,200);
        post(subject,path(id)+"/repair-continuation",original,200);post(subject,path(id)+"/repair-continuation",next,200);
        var changed=new HashMap<>(original);changed.put("note","Changed body must not reuse an archived idempotency identifier");
        post(subject,path(id)+"/repair-continuation",changed,409);finish(id);tick();
        verify(provider,times(2)).editAnimation(any());verify(provider,never()).submit(anyBoolean(),any());
        assertThat(step(id,"idle-west")).isEqualTo(kept);assertThat(read(id).path("seedReview")).isEqualTo(seeds);
        assertThat(read(id).at("/qualityPolicy/repairContinuationHistory/0/requestId").asText()).isEqualTo(original.get("requestId").toString());
        assertThat(read(id).at("/qualityPolicy/repairContinuationHistory")).hasSize(1);
        post(subject,path(id)+"/repair-continuation",original,200);tick();verify(provider,times(2)).editAnimation(any());
    }
    @Test void legacyMirroredIdleGetsOnlyOneSeedFallbackAndRawFailureStillBlocksApproval() throws Exception {
        UUID id=continuationFixture("idle-west");var original=legacyMirroredIdle(id);var before=read(id);
        var beforeHistory=json.readTree(jdbc.queryForObject("SELECT attempt_history::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-west'",String.class,id));
        when(codec.seedIdle(anyString(),any(),anyInt())).thenReturn(json.valueToTree(Map.of("seedIdle",true)));
        when(provider.editAnimation(any())).thenReturn(UUID.randomUUID());
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("west"))).thenAnswer(c->{boolean ok=calls.incrementAndGet()==1;
            return json.valueToTree(Map.of("passed",ok,"issues",ok?List.of():List.of("IDLE_MOTION")));});
        var next=continuationBody(id,"idle-west");clearInvocations(provider);
        post(subject,path(id)+"/repair-continuation",next,200);post(subject,path(id)+"/repair-continuation",next,200);
        post(subject,path(id)+"/repair-continuation",original,200);finish(id);
        verify(provider,times(1)).editAnimation(any());verify(provider,never()).submit(anyBoolean(),any());
        verify(codec).seedIdle(eq("west"),eq(png),anyInt());
        assertThat(step(id,"idle-west").path("repairCount").asInt()).isEqualTo(4);
        assertThat(step(id,"idle-west").at("/qualityReport/rawEditReview/passed").asBoolean()).isFalse();
        assertThat(read(id).path("failureCode").asText()).isEqualTo("QUALITY_REPAIR_EXHAUSTED");review(id,false,"APPROVE",409);
        for(var old:before.path("steps"))if(!old.path("label").asText().equals("idle-west"))assertThat(step(id,old.path("label").asText())).isEqualTo(old);
        assertThat(read(id).path("seedReview")).isEqualTo(before.path("seedReview"));
        var history=json.readTree(jdbc.queryForObject("SELECT attempt_history::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-west'",String.class,id));
        assertThat(history.size()).isEqualTo(beforeHistory.size()+1);
        for(int i=0;i<beforeHistory.size();i++)assertThat(history.get(i)).isEqualTo(beforeHistory.get(i));
        assertThat(history.get(history.size()-1).path("repairCount").asInt()).isEqualTo(3);
        post(subject,path(id)+"/repair-continuation",original,200);post(subject,path(id)+"/repair-continuation",next,200);
        post(subject,path(id)+"/repair-continuation",continuationBody(id,"idle-west"),409);tick();verify(provider,times(1)).editAnimation(any());
        assertThatThrownBy(()->jdbc.update("UPDATE shelter.styled_asset_steps SET repair_count=6 WHERE job_id=? AND label='idle-west'",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE shelter.styled_asset_steps SET repair_count=5 WHERE job_id=? AND label='walk-west'",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void legacyFallbackRejectsOtherDefectsPriorSeedRepairAndUnspentNormalBudget() throws Exception {
        UUID id=continuationFixture("idle-west");legacyMirroredIdle(id);var body=continuationBody(id,"idle-west");
        jdbc.update("UPDATE shelter.styled_asset_steps SET quality_report=quality_report || '{\"issues\":[\"CANVAS_CLIPPING\"]}'::jsonb WHERE job_id=? AND label='idle-west'",id);
        post(subject,path(id)+"/repair-continuation",body,409);
        jdbc.update("UPDATE shelter.styled_asset_steps SET quality_report=quality_report || '{\"issues\":[\"IDLE_MOTION\"]}'::jsonb WHERE job_id=? AND label='idle-west'",id);
        jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=jsonb_set(quality_policy,'{repairContinuation,plans,idle-west,strategy}',to_jsonb(CAST(? AS text))) WHERE id=?",StyledSpriteCodec.SEED_IDLE_VERSION,id);
        post(subject,path(id)+"/repair-continuation",body,409);
        jdbc.update("UPDATE shelter.styled_asset_steps SET quality_report=quality_report || '{\"passed\":false,\"issues\":[\"IDENTITY_DRIFT\"]}'::jsonb WHERE job_id=? AND label='sit-south'",id);
        post(subject,path(id)+"/repair-continuation",continuationBody(id,"sit-south"),409);
        assertThat(step(id,"idle-west").path("repairCount").asInt()).isEqualTo(3);
        assertThat(step(id,"sit-south").path("repairCount").asInt()).isZero();
    }
    UUID tailPlan() throws Exception {
        UUID id=request();
        jdbc.update("UPDATE shelter.asset_jobs SET action_plan=action_plan || '[\"TAIL_WAG\"]'::jsonb WHERE id=?",id);
        int ordinal=13;for(String d:List.of("south","north","west","east"))jdbc.update(
            "INSERT INTO shelter.styled_asset_steps(job_id,ordinal,label,action,direction) VALUES (?,?,?,'TAIL_WAG',?)",id,ordinal++,"tail_wag-"+d,d);
        when(codec.tailEdit(anyString(),any(),anyInt())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("edited",true));});
        when(provider.editAnimation(any())).thenAnswer(c->{outsideTransaction();return UUID.randomUUID();});
        return id;
    }
    @Test void secondLowTailRepairEditsPriorStripOnceAndKeepsRawVerdictAfterSeedLock() throws Exception {
        var count=new java.util.concurrent.atomic.AtomicInteger();
        when(quality.review(any(),anyList(),anyList(),eq("TAIL_WAG"),eq("west"))).thenAnswer(c->{
            int n=count.incrementAndGet();boolean ok=n==3; // restored result passes, raw edited strip still fails
            return json.valueToTree(Map.of("passed",ok,"issues",ok?List.of():List.of("CANVAS_CLIPPING")));
        });
        UUID id=tailPlan();tick();tick();review(id,true,"APPROVE",200);finish(id);
        var j=read(id);assertThat(j.path("failureCode").asText()).isEqualTo("QUALITY_REPAIR_EXHAUSTED");
        verify(provider,times(18)).submit(anyBoolean(),any());verify(provider,times(1)).editAnimation(any());
        verify(codec,times(1)).tailEdit(eq("west"),any(),anyInt());
        var step=j.path("steps").valueStream().filter(n->n.path("label").asText().equals("tail_wag-west")).findFirst().orElseThrow();
        assertThat(step.path("repairCount").asInt()).isEqualTo(2);
        assertThat(step.at("/result/rawEdit/sha256").asText()).matches("[a-f0-9]{64}");
        assertThat(step.at("/qualityReport/rawEditReview/passed").asBoolean()).isFalse();
        assertThat(step.at("/qualityReport/passed").asBoolean()).isFalse();review(id,false,"APPROVE",409);
        assertThat(jdbc.queryForObject("SELECT attempt_history::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='tail_wag-west'",String.class,id)).contains("providerJobId","CANVAS_CLIPPING");
        tick();verify(provider,times(1)).editAnimation(any());
    }
    @Test void passingRawAndRestoredEditCanBeReviewedAndReadWithoutExtraGeneration() throws Exception {
        var count=new java.util.concurrent.atomic.AtomicInteger();
        when(quality.review(any(),anyList(),anyList(),eq("TAIL_WAG"),eq("east"))).thenAnswer(c->{
            boolean ok=count.incrementAndGet()>2;return json.valueToTree(Map.of("passed",ok,"issues",ok?List.of():List.of("TAIL_CARRIAGE")));
        });
        UUID id=tailPlan();tick();tick();review(id,true,"APPROVE",200);finish(id);review(id,false,"APPROVE",200);
        verify(provider,times(1)).editAnimation(any());publicStatus(200);publicStatus(200);verify(provider,times(1)).editAnimation(any());
        assertThat(objects.keySet().stream().filter(k->k.contains("raw-edits/"))).hasSize(1);
    }
    @Test void existingUnknownTailWithoutMarginPolicyKeepsBoundedRegeneration() throws Exception {
        when(quality.contract(any(),any())).thenReturn(json.valueToTree(Map.of("tailCarriage","UNKNOWN")));
        when(quality.review(any(),anyList(),anyList(),eq("TAIL_WAG"),eq("west"))).thenReturn(json.valueToTree(Map.of("passed",false,"issues",List.of("CANVAS_CLIPPING"))));
        UUID id=tailPlan();jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=quality_policy-'marginRepair' WHERE id=?",id);
        tick();tick();review(id,true,"APPROVE",200);finish(id);verify(provider,never()).editAnimation(any());
        verify(provider,times(19)).submit(anyBoolean(),any());review(id,false,"APPROVE",409);
    }
    @Test void newUnknownTailClippingUsesActionSpecificMarginEditWithoutForcingLowTail() throws Exception {
        when(quality.contract(any(),any())).thenReturn(json.valueToTree(Map.of("tailCarriage","UNKNOWN")));
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(quality.review(any(),anyList(),anyList(),eq("TAIL_WAG"),eq("west"))).thenAnswer(c->{
            boolean ok=calls.incrementAndGet()>2;
            return json.valueToTree(Map.of("passed",ok,"issues",ok?List.of():List.of("CANVAS_CLIPPING")));
        });
        when(codec.marginEdit(anyString(),anyString(),any(),anyInt())).thenReturn(json.valueToTree(Map.of("marginEdited",true)));
        UUID id=tailPlan();tick();tick();review(id,true,"APPROVE",200);finish(id);
        verify(codec).marginEdit(eq("TAIL_WAG"),eq("west"),any(),anyInt());
        verify(codec,never()).tailEdit(anyString(),any(),anyInt());
        verify(provider,times(18)).submit(anyBoolean(),any());verify(provider,times(1)).editAnimation(any());
        review(id,false,"APPROVE",200);
    }
    @Test void everyRuleBeyondOldCandidateLimitAndSameIssueReachesFirstMotionAndReview()throws Exception {
        learningPair();lessonWorker.tick();lessonWorker.tick();cloneActiveRules(onlyLesson(),24);
        nextLearningDog();UUID next=request();tick();tick();review(next,true,"APPROVE",200);finish(next);
        var pinned=json.readTree(jdbc.queryForObject("SELECT learned_lessons::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='sit-west'",String.class,next));
        assertThat(pinned.size()).isEqualTo(25);
        var inputs=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(promptAgent).compose(inputs.capture(),anyString(),anyInt());
        assertThat(inputs.getValue()).isEqualTo(pinned);
        assertThat(step(next,"sit-west").at("/qualityReport/learnedLessons")).isEqualTo(pinned);
        var record=json.readTree(jdbc.queryForObject("SELECT input::text FROM shelter.styled_lesson_prompts WHERE job_id=? AND state='READY'",String.class,next));
        assertThat(record.path("lessons")).isEqualTo(pinned);
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(validation->'preserved') FROM shelter.styled_lesson_prompts WHERE job_id=?",Integer.class,next)).isEqualTo(25);
    }
    @Test void allSeedRulesReachFirstGenerationAndQualityReview()throws Exception {
        UUID source=seedLearningPair();review(source,true,"APPROVE",200);finish(source);lessonWorker.tick();lessonWorker.tick();cloneActiveRules(onlyLesson(),4);
        nextLearningDog();UUID next=request();tick();tick();
        var pinned=json.readTree(jdbc.queryForObject("SELECT learned_lessons::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='character'",String.class,next));
        assertThat(pinned.size()).isEqualTo(5);verify(seedQuality).review(any(),anyList(),eq(pinned));
        var input=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(promptAgent).compose(input.capture(),anyString(),anyInt());
        assertThat(input.getValue()).isEqualTo(pinned);assertThat(read(next).path("status").asText()).isEqualTo("SEED_REVIEW");
    }
    @Test void meaningLossIsAutomaticallyRewrittenWithEveryRuleBeforeImagePurchase()throws Exception {
        UUID source=seedLearningPair();review(source,true,"APPROVE",200);finish(source);lessonWorker.tick();lessonWorker.tick();cloneActiveRules(onlyLesson(),3);
        var rejected=json.valueToTree(Map.of("preserved",List.of(true,true,false,true),"compatibleWithBase",true,"noNewRequirements",true));
        var accepted=json.valueToTree(Map.of("preserved",Collections.nCopies(4,true),"compatibleWithBase",true,"noNewRequirements",true));
        doReturn(rejected,accepted).when(promptAgent).verify(any(),anyString(),any(),anyInt());
        nextLearningDog();UUID next=request();clearInvocations(provider);tick();
        var pinned=json.readTree(jdbc.queryForObject("SELECT learned_lessons::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='character'",String.class,next));
        verify(promptAgent).rewrite(eq(pinned),anyString(),anyInt(),any(),eq(rejected));
        verify(provider).submit(eq(true),any());
        assertThat(jdbc.queryForObject("SELECT state FROM shelter.styled_lesson_prompts WHERE job_id=?",String.class,next)).isEqualTo("READY");
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(history) FROM shelter.styled_lesson_prompts WHERE job_id=?",Integer.class,next)).isEqualTo(4);
    }
    @Test void failedMeaningVerificationPreservesAllRulesAndStopsBeforeImagePurchase()throws Exception {
        UUID source=seedLearningPair();review(source,true,"APPROVE",200);finish(source);lessonWorker.tick();lessonWorker.tick();cloneActiveRules(onlyLesson(),3);
        doReturn(json.valueToTree(Map.of("preserved",List.of(true,true,false,true),"compatibleWithBase",true,"noNewRequirements",true))).when(promptAgent).verify(any(),anyString(),any(),anyInt());
        nextLearningDog();UUID next=request();clearInvocations(provider);tick();
        assertThat(read(next).path("failureCode").asText()).isEqualTo("LESSON_PROMPT_MEANING_LOST");verifyNoInteractions(provider);
        assertThat(jdbc.queryForObject("SELECT state FROM shelter.styled_lesson_prompts WHERE job_id=?",String.class,next)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT validation->'preserved'->>2 FROM shelter.styled_lesson_prompts WHERE job_id=?",String.class,next)).isEqualTo("false");
        verify(promptAgent,times(2)).rewrite(any(),anyString(),anyInt(),any(),any());
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(history) FROM shelter.styled_lesson_prompts WHERE job_id=?",Integer.class,next)).isEqualTo(6);
        post(opSubject,"/v1/operations/styled-asset-jobs/"+next+"/recover",Map.of(),200);tick();
        verify(promptAgent,times(1)).compose(any(),anyString(),anyInt());verifyNoInteractions(provider);
    }
    @Test void overlongCompactionCannotDropRulesToPurchaseAnImage()throws Exception {
        UUID source=seedLearningPair();review(source,true,"APPROVE",200);finish(source);lessonWorker.tick();lessonWorker.tick();cloneActiveRules(onlyLesson(),3);
        doAnswer(c->json.valueToTree(Map.of("prompt","x".repeat(c.<Integer>getArgument(2)+1)))).when(promptAgent).compose(any(),anyString(),anyInt());
        nextLearningDog();UUID next=request();clearInvocations(provider);tick();
        assertThat(read(next).path("failureCode").asText()).isEqualTo("LESSON_PROMPT_INVALID");verifyNoInteractions(provider);
        verify(promptAgent,never()).verify(any(),anyString(),any(),anyInt());
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(learned_lessons) FROM shelter.styled_asset_steps WHERE job_id=? AND label='character'",Integer.class,next)).isEqualTo(4);
    }
    @Test void learningRecoveryIncludesAllRequiredRulesBeyondTwo()throws Exception {
        UUID id=learningRecoveryJob(true);recoveryTick();lessonWorker.tick();lessonWorker.tick();cloneActiveRules(onlyLesson(),3);
        recoveryTick();assertThat(recoveryState(id).path("requiredLessons").size()).isEqualTo(4);
        clearInvocations(provider);tick();tick();finish(id);
        verify(provider,times(1)).editAnimation(any());assertThat(step(id,"idle-south").at("/qualityReport/learnedLessons").size()).isEqualTo(4);
        assertThat(recoveryState(id).path("state").asText()).isEqualTo("COMPLETED");
    }
    @Test void cachedPromptIsReusedAndChangedPayloadOrRevokedRuleCannotBeSubmitted()throws Exception {
        UUID source=seedLearningPair();review(source,true,"APPROVE",200);finish(source);lessonWorker.tick();lessonWorker.tick();cloneActiveRules(onlyLesson(),3);
        nextLearningDog();UUID next=request();var work=store.claim();var rules=lessonStore.pin(work);
        String description=promptComposer.describe(work,rules,"Keep native pixel art.",2000);
        assertThat(promptComposer.describe(work,rules,"Keep native pixel art.",2000)).isEqualTo(description);
        verify(promptAgent,times(1)).compose(any(),anyString(),anyInt());verify(promptAgent,times(1)).verify(any(),anyString(),any(),anyInt());
        try {store.reserve(work,json.valueToTree(Map.of("description","Different prompt missing the rules.")));fail("An unverified payload must be blocked");}
        catch(RuntimeException error){assertThat(error).hasMessage("LEARNED_RULE_NOT_APPLIED");}
        lessonStore.disable(opSubject,UUID.fromString(rules.get(0).path("id").asText()),json.valueToTree(Map.of("note","Disable after verification, before provider purchase")));
        try {store.reserve(work,json.valueToTree(Map.of("description",description)));fail("Revoked rules must be blocked");}
        catch(RuntimeException error){assertThat(error).hasMessage("LESSON_SOURCE_CHANGED");}
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_submissions WHERE job_id=?",Integer.class,next)).isZero();
    }
    void cloneActiveRules(UUID original,int count) {
        for(int i=0;i<count;i++)jdbc.update("""
            INSERT INTO shelter.styled_quality_lessons(source_example_id,source_job_id,source_label,action,direction,tail,issue,rules_sha256,status,candidate,candidate_sha256,validation_examples,validation)
            SELECT source_example_id,source_job_id,?,action,direction,tail,issue,rules_sha256,status,candidate,candidate_sha256,validation_examples,validation
            FROM shelter.styled_quality_lessons WHERE id=?
            ""","fixture-copy-"+i,original);
    }
    @Test void seedLessonsWaitForHumanPositiveThenReachNextDogGenerationAndReview()throws Exception {
        UUID first=seedLearningPair();UUID lesson=onlyLesson();
        lessonWorker.tick();assertThat(lessonStatus(lesson)).isEqualTo("WAITING_EVIDENCE");verifyNoInteractions(lessonAgent);
        review(first,true,"APPROVE",200);finish(first);
        lessonWorker.tick();lessonWorker.tick();assertThat(lessonStatus(lesson)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=? AND action='BASE'",Integer.class,first)).isEqualTo(2);
        nextLearningDog();UUID next=request();clearInvocations(provider,seedQuality);tick();tick();
        var pinned=json.readTree(jdbc.queryForObject("SELECT learned_lessons::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='character'",String.class,next));
        assertThat(pinned.size()).isEqualTo(1);assertThat(pinned.get(0).path("id").asText()).isEqualTo(lesson.toString());
        var payload=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(provider).submit(eq(true),payload.capture());
        assertThat(payload.getValue().path("description").asText()).contains(pinned.get(0).path("prevention").asText());
        verify(seedQuality).review(any(),anyList(),eq(pinned));
        assertThat(read(next).at("/steps/0/qualityReport/learnedLessons")).isEqualTo(pinned);
        assertThat(read(next).path("status").asText()).isEqualTo("SEED_REVIEW");
        verify(provider,never()).submit(eq(false),any());
        review(next,true,"APPROVE",200);finish(next);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_asset_steps WHERE job_id=? AND action<>'BASE' AND learned_lessons<>'[]'::jsonb",Integer.class,next)).isZero();
    }
    Map<String,Object> referenceBody(byte[] frame,String assessment)throws Exception {
        String hash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(frame));
        String photoHash=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(png));
        return new HashMap<>(Map.of("photoId",photo,"sourcePhotoSha256",photoHash,"expectedSeedHashes",Map.of("south",hash,"north",hash,"west",hash,"east",hash),
            "assessment",assessment,"issues",assessment.equals("NEGATIVE")?List.of("EYE_READABILITY"):List.of(),"note","Previously reviewed native reference; preserve source pixels and original AI judgment"));
    }
    JsonNode reference(UUID caller,byte[] frame,Object body,int expected)throws Exception {
        var metadata=new org.springframework.mock.web.MockPart("metadata",json.writeValueAsBytes(body));
        metadata.getHeaders().setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        var req=multipart("/v1/shelter-admin/dogs/"+dog+"/styled-seed-examples").part(metadata);
        for(String d:List.of("south","north","west","east")) {
            var part=new org.springframework.mock.web.MockPart(d,d+".png",frame);
            part.getHeaders().setContentType(org.springframework.http.MediaType.IMAGE_PNG);req.part(part);
        }
        if(caller!=null)req.header("Authorization",bearer(caller));
        return json.readTree(mvc.perform(req).andExpect(status().is(expected)).andReturn().getResponse().getContentAsString()).path("data");
    }
    @Test void importedReferencesKeepAiVerdictAndLearnHumanFailureOnlyAfterPositiveApproval()throws Exception {
        var badBody=referenceBody(png,"NEGATIVE");UUID bad=UUID.fromString(reference(subject,png,badBody,202).path("id").asText());
        assertThat(reference(subject,png,badBody,202).path("id").asText()).isEqualTo(bad.toString());
        assertThat(read(bad).path("qualityPolicy").has("seedTailEvidenceVersion")).isFalse(); // Imported references use their separate manual-only review path.
        tick();assertThat(read(bad).at("/steps/0/qualityReport/passed").asBoolean()).isTrue();
        var example=json.readTree(jdbc.queryForObject("SELECT report::text FROM shelter.styled_quality_examples WHERE job_id=?",String.class,bad));
        assertThat(example.path("passed").asBoolean()).isFalse();assertThat(example.at("/aiAssessment/passed").asBoolean()).isTrue();
        assertThat(example.path("assessmentSource").asText()).isEqualTo("HUMAN_NEGATIVE_FEEDBACK");review(bad,true,"APPROVE",409);
        UUID lesson=onlyLesson();lessonWorker.tick();assertThat(lessonStatus(lesson)).isEqualTo("WAITING_EVIDENCE");
        var changed=ImageIO.read(new ByteArrayInputStream(png));changed.setRGB(12,9,0xffcdbbab);
        var bytes=new ByteArrayOutputStream();ImageIO.write(changed,"png",bytes);byte[] good=bytes.toByteArray();
        UUID positive=UUID.fromString(reference(subject,good,referenceBody(good,"POSITIVE"),202).path("id").asText());tick();
        lessonWorker.tick();verifyNoInteractions(lessonAgent);review(positive,true,"APPROVE",200);tick();
        assertThat(read(positive).path("status").asText()).isEqualTo("SEED_REVIEW");assertThat(read(positive).path("steps")).hasSize(1);
        assertThat(read(positive).path("actionPlan").valueStream().map(JsonNode::asText)).containsExactly("BASE");
        review(positive,false,"APPROVE",409);publicStatus(404);verifyNoInteractions(provider);
        assertThat(get(subject,path(positive)+"/preview",200).at("/data/referenceOnly").asBoolean()).isTrue();
        assertThatThrownBy(()->jdbc.update("UPDATE shelter.asset_jobs SET status='APPROVED' WHERE id=?",positive)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        when(lessonAgent.proposeSeeds(any(),any())).thenReturn(json.valueToTree(Map.of("prevention","Keep filled pupils readable against dark fur.","criterion","Pupil clusters merge with adjacent fur.")));
        when(lessonAgent.replaySeeds(any(),any(),anyList())).thenAnswer(c->seedReplayAnswer(c.getArgument(2),false));
        lessonWorker.tick();lessonWorker.tick();assertThat(lessonStatus(lesson)).isEqualTo("ACTIVE");
        nextLearningDog();when(seedQuality.review(any(),anyList(),any())).thenAnswer(c->seedReport(c.getArgument(1),true));
        UUID generated=request();tick();tick();
        assertThat(read(generated).at("/steps/0/qualityReport/learnedLessons/0/id").asText()).isEqualTo(lesson.toString());
        verify(provider,times(1)).submit(eq(true),any());verify(provider,never()).submit(eq(false),any());
    }
    @Test void referenceUploadRejectsUnauthorizedChangedOrOversizedInputsBeforePersisting()throws Exception {
        var body=referenceBody(png,"POSITIVE");reference(null,png,body,401);reference(UUID.randomUUID(),png,body,403);
        body.put("sourcePhotoSha256","0".repeat(64));reference(subject,png,body,409);
        body=referenceBody(png,"POSITIVE");body.put("expectedSeedHashes",Map.of("south","0".repeat(64)));reference(subject,png,body,409);
        reference(subject,new byte[65537],referenceBody(png,"POSITIVE"),413);
        reference(subject,new byte[]{1,2,3},referenceBody(png,"POSITIVE"),422);
        verify(storage,never()).put(anyString(),any());verifyNoInteractions(provider,seedQuality);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_jobs WHERE dog_id=?",Integer.class,dog)).isZero();
    }
    @Test void supposedPositiveRejectedByVisionIsNotNegativeLearningEvidenceOrRegenerated()throws Exception {
        when(seedQuality.review(any(),anyList())).thenAnswer(c->seedReport(c.getArgument(1),false));
        UUID id=UUID.fromString(reference(subject,png,referenceBody(png,"POSITIVE"),202).path("id").asText());tick();tick();
        assertThat(read(id).path("failureCode").asText()).isEqualTo("SEED_QUALITY_REVIEW_REQUIRED");review(id,true,"APPROVE",409);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=?",Integer.class,id)).isZero();
        verifyNoInteractions(provider);assertThat(read(id).at("/steps/0/repairCount").asInt()).isZero();
    }
    @Test void importedReferencesRejectConflictingAssessmentAndChangedPhotoBeforeVision()throws Exception {
        UUID id=UUID.fromString(reference(subject,png,referenceBody(png,"POSITIVE"),202).path("id").asText());
        reference(subject,png,referenceBody(png,"NEGATIVE"),409);
        when(storage.photo(any(),anyString(),anyString())).thenReturn(new byte[]{9});tick();
        assertThat(read(id).path("failureCode").asText()).isEqualTo("SOURCE_PHOTO_CHANGED");verifyNoInteractions(seedQuality,provider);
    }
    @Test void referencePermissionRevocationBeforeWorkerPreventsModelUpload()throws Exception {
        UUID id=UUID.fromString(reference(subject,png,referenceBody(png,"POSITIVE"),202).path("id").asText());
        jdbc.update("UPDATE shelter.asset_source_permissions SET revoked_at=now() WHERE id=?",permission);tick();
        assertThat(read(id).path("status").asText()).isEqualTo("CANCELLED");verifyNoInteractions(seedQuality,provider);
    }
    @Test void concurrentReferenceImportsReuseOneJobAndOneVisionReview()throws Exception {
        var body=referenceBody(png,"POSITIVE");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var a=pool.submit(()->reference(subject,png,body,202).path("id").asText());
            var b=pool.submit(()->reference(subject,png,body,202).path("id").asText());
            assertThat(a.get(10,TimeUnit.SECONDS)).isEqualTo(b.get(10,TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_jobs WHERE dog_id=?",Integer.class,dog)).isEqualTo(1);
        tick();tick();verify(seedQuality,times(1)).review(any(),anyList());verifyNoInteractions(provider);
    }
    @Test void failedReferenceStorageNeverEnqueuesPartialFrames()throws Exception {
        doThrow(new RuntimeException("Simulated private storage failure")).when(storage).put(anyString(),any());
        reference(subject,png,referenceBody(png,"POSITIVE"),500);tick();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_jobs WHERE dog_id=?",Integer.class,dog)).isZero();
        verifyNoInteractions(seedQuality,provider);
    }
    @Test void seedRuleRejectingApprovedGoodExampleNeverActivates()throws Exception {
        UUID first=seedLearningPair();review(first,true,"APPROVE",200);finish(first);UUID lesson=onlyLesson();
        when(lessonAgent.replaySeeds(any(),any(),anyList())).thenAnswer(c->seedReplayAnswer(c.getArgument(2),true));
        lessonWorker.tick();lessonWorker.tick();assertThat(lessonStatus(lesson)).isEqualTo("REJECTED");
    }
    @Test void seedPhotoOrSeedChangesStopLearningBeforeModelUpload()throws Exception {
        UUID first=seedLearningPair();review(first,true,"APPROVE",200);finish(first);UUID lesson=onlyLesson();
        when(storage.photo(any(),anyString(),anyString())).thenReturn(new byte[]{1,2,3});
        lessonWorker.tick();assertThat(lessonStatus(lesson)).isEqualTo("FAILED");verifyNoInteractions(lessonAgent);
    }
    @Test void revokedSeedPermissionPreventsLearning()throws Exception {
        UUID first=seedLearningPair();review(first,true,"APPROVE",200);finish(first);UUID lesson=onlyLesson();
        jdbc.update("UPDATE shelter.asset_source_permissions SET revoked_at=now() WHERE id=?",permission);
        lessonWorker.tick();assertThat(lessonStatus(lesson)).isEqualTo("DISABLED");verifyNoInteractions(lessonAgent);
    }
    @Test void disabledSeedRuleCannotReachNextProviderRequest()throws Exception {
        UUID first=seedLearningPair();review(first,true,"APPROVE",200);finish(first);UUID lesson=onlyLesson();
        lessonWorker.tick();lessonWorker.tick();
        nextLearningDog();UUID next=request();
        doAnswer(c->{
            lessonStore.disable(opSubject,lesson,json.valueToTree(Map.of("note","Disable before any new seed submission")));
            return json.valueToTree(Map.of("prompt","Preserve all filled pupils and the original dog."));
        }).when(promptAgent).compose(any(),anyString(),anyInt());
        clearInvocations(provider);tick();verifyNoInteractions(provider);
        assertThat(read(next).path("failureCode").asText()).isEqualTo("LESSON_SOURCE_CHANGED");
        verify(promptAgent,never()).verify(any(),anyString(),any(),anyInt());
    }

    @Test void seedRecheckRecordsExampleOnceWithoutCreatingASeedPositiveApproval()throws Exception {
        UUID id=request();tick();tick();var body=seedRecheckBody(id);
        post(subject,path(id)+"/quality-recheck",body,200);tick();
        post(subject,path(id)+"/quality-recheck",body,200);tick();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=? AND action='BASE'",Integer.class,id)).isEqualTo(1);
        assertThat(read(id).path("seedReview").isNull()).isTrue();
        verify(provider,times(1)).submit(eq(true),any());
    }
    UUID seedLearningPair()throws Exception {
        var changed=ImageIO.read(new ByteArrayInputStream(png));changed.setRGB(12,9,0xffcdbbab);
        var out=new ByteArrayOutputStream();ImageIO.write(changed,"png",out);byte[] good=out.toByteArray();
        var generations=new java.util.concurrent.atomic.AtomicInteger();var inspections=new java.util.concurrent.atomic.AtomicInteger();
        when(provider.poll(any(),eq(true))).thenAnswer(c->{outsideTransaction();String b=Base64.getEncoder().encodeToString(generations.getAndIncrement()==0?png:good);
            return json.valueToTree(Map.of("status","COMPLETED","directions",Map.of("south",b,"north",b,"west",b,"east",b)));});
        when(seedQuality.review(any(),anyList())).thenAnswer(c->{outsideTransaction();return seedReport(c.getArgument(1),inspections.getAndIncrement()>0);});
        when(seedQuality.review(any(),anyList(),any())).thenAnswer(c->{outsideTransaction();return seedReport(c.getArgument(1),true);});
        when(lessonAgent.proposeSeeds(any(),any())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of(
            "prevention","Separate filled pupils from adjacent fur with restrained local contrast.",
            "criterion","A front or side pupil disappears into surrounding fur without a distinct filled shape."));});
        when(lessonAgent.replaySeeds(any(),any(),anyList())).thenAnswer(c->{outsideTransaction();return seedReplayAnswer(c.getArgument(2),false);});
        UUID id=request();for(int i=0;i<4;i++)tick();assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");return id;
    }
    JsonNode seedReplayAnswer(List<StyledLessonAgent.Case> cases,boolean falsePositive) {
        return json.valueToTree(Map.of("safeAndGeneral",true,"reason","Synthetic wiring check, not actual vision quality","cases",cases.stream().map(c->{
            boolean bad=falsePositive || !c.report().path("passed").asBoolean();
            return Map.of("key",c.key(),"violates",bad,"directions",bad?List.of("south","west","east"):List.of());}).toList()));
    }

    @Test void automaticLessonsReplayFailuresAndPassesThenReachAnotherDogsFirstGeneration() throws Exception {
        UUID first=learningPair();UUID lesson=onlyLesson();
        assertThat(lessonStatus(lesson)).isEqualTo("WAITING_EVIDENCE");
        lessonWorker.tick();assertThat(lessonStatus(lesson)).isEqualTo("CANDIDATE");
        lessonWorker.tick();assertThat(lessonStatus(lesson)).isEqualTo("ACTIVE");
        assertThat(read(first).path("status").asText()).isEqualTo("REVIEW"); // learning never publishes a dog
        var rule=get(opSubject,"/v1/operations/styled-quality-lessons/"+lesson,200).path("data");
        assertThat(rule.at("/validation/passed").asBoolean()).isTrue();
        assertThat(rule.path("events").valueStream().map(n->n.path("event").asText())).contains("OBSERVED","PROPOSING","PROPOSED","VALIDATING","ACTIVATED");
        get(subject,"/v1/operations/styled-quality-lessons",403);get(null,"/v1/operations/styled-quality-lessons",401);
        nextLearningDog();UUID next=request();tick();tick();review(next,true,"APPROVE",200);finish(next);
        String reports=jdbc.queryForObject("SELECT quality_report::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='sit-west'",String.class,next);
        assertThat(reports).contains(lesson.toString(),"learnedLessons");
        var captured=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(provider,atLeastOnce()).submit(eq(false),captured.capture());
        assertThat(captured.getAllValues().stream().anyMatch(n->n.path("description").asText().contains(learnedPrevention()))).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_asset_steps WHERE job_id=? AND label<>'sit-west' AND learned_lessons<>'[]'::jsonb",Integer.class,next)).isZero();
        post(subject,"/v1/operations/styled-quality-lessons/"+lesson+"/disable",Map.of("note","Stop this lesson for later generations"),403);
        post(opSubject,"/v1/operations/styled-quality-lessons/"+lesson+"/disable",Map.of("note","Stop this lesson for later generations"),200);
        clearInvocations(provider);var request=json.valueToTree(input());((tools.jackson.databind.node.ObjectNode)request.path("traits")).put("seed",43);
        UUID after=UUID.fromString(post(subject,"/v1/shelter-admin/dogs/"+dog+"/styled-assets",request,202).at("/data/id").asText());
        jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=quality_policy-'seedTailEvidenceVersion'-'automaticApproval'-'seedMotionMargin'-'lessonRevision'-'recoveryVersion'-'motionFrameSize' WHERE id=?",after);
        tick();tick();review(after,true,"APPROVE",200);finish(after);
        var afterPayloads=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(provider,atLeastOnce()).submit(eq(false),afterPayloads.capture());
        assertThat(afterPayloads.getAllValues().stream().anyMatch(n->n.path("description").asText().contains(learnedPrevention()))).isFalse();
        verify(lessonAgent,times(1)).propose(any(),any());verify(lessonAgent,times(1)).replay(any(),any(),anyList());
    }
    @Test void lessonThatRejectsKnownGoodFramesNeverBecomesActive() throws Exception {
        learningPair();UUID lesson=onlyLesson();
        when(lessonAgent.replay(any(),any(),anyList())).thenAnswer(c->replayAnswer(c.getArgument(2),true));
        lessonWorker.tick();lessonWorker.tick();assertThat(lessonStatus(lesson)).isEqualTo("REJECTED");
        lessonWorker.tick();verify(lessonAgent,times(1)).replay(any(),any(),anyList());
    }
    @Test void learnerWaitsForPositiveEvidenceWithoutAnyModelCall() throws Exception {
        learningPair();UUID lesson=onlyLesson();
        jdbc.update("DELETE FROM shelter.styled_quality_examples WHERE job_id IN (SELECT id FROM shelter.asset_jobs WHERE dog_id=?) AND passed",dog);
        lessonWorker.tick();verifyNoInteractions(lessonAgent);assertThat(lessonStatus(lesson)).isEqualTo("WAITING_EVIDENCE");
    }
    @Test void concurrentLearningClaimsAndInterruptionsDoNotRepeatModelCalls() throws Exception {
        learningPair();UUID lesson=onlyLesson();var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->lessonStore.claim());var b=pool.submit(()->lessonStore.claim());
            assertThat(java.util.stream.Stream.of(a.get(),b.get()).filter(Objects::nonNull).count()).isEqualTo(1);
        }finally{pool.shutdownNow();}
        jdbc.update("UPDATE shelter.styled_quality_lessons SET lease_until=now()-interval '1 second' WHERE id=?",lesson);
        lessonWorker.tick();lessonWorker.tick();assertThat(lessonStatus(lesson)).isEqualTo("FAILED");verifyNoInteractions(lessonAgent);
    }
    @Test void replayThatMissesOnlyTheFinalBadFrameIsRejected() throws Exception {
        learningPair();UUID lesson=onlyLesson();
        when(lessonAgent.replay(any(),any(),anyList())).thenAnswer(c->{var result=replayAnswer(c.getArgument(2),false);
            for(var item:result.path("cases"))if(item.path("violates").asBoolean())((tools.jackson.databind.node.ObjectNode)item).set("frames",json.valueToTree(List.of(3,4,5,6,7)));
            return result;});
        lessonWorker.tick();lessonWorker.tick();assertThat(lessonStatus(lesson)).isEqualTo("REJECTED");
    }
    @Test void disabledDuringPayloadConstructionNeverReachesProvider() throws Exception {
        learningPair();UUID lesson=onlyLesson();lessonWorker.tick();lessonWorker.tick();
        nextLearningDog();UUID next=request();tick();tick();review(next,true,"APPROVE",200);
        doAnswer(c->{lessonStore.disable(opSubject,lesson,json.valueToTree(Map.of("note","Disabled during pending prompt verification")));
            return json.valueToTree(Map.of("preserved",List.of(true),"compatibleWithBase",true,"noNewRequirements",true));
        }).when(promptAgent).verify(any(),anyString(),any(),anyInt());
        clearInvocations(provider);finish(next);
        var calls=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(provider,atLeastOnce()).submit(eq(false),calls.capture());
        assertThat(calls.getAllValues().stream().anyMatch(n->n.path("description").asText().contains(learnedPrevention()))).isFalse();
        assertThat(lessonStatus(lesson)).isEqualTo("DISABLED");assertThat(read(next).path("failureCode").asText()).isEqualTo("LESSON_SOURCE_CHANGED");
    }

    @Test void revokedEvidencePreventsLearningWithoutAnyModelUpload() throws Exception {
        learningPair();UUID lesson=onlyLesson();
        jdbc.update("UPDATE shelter.asset_source_permissions SET revoked_at=now() WHERE id=?",permission);
        lessonWorker.tick();assertThat(lessonStatus(lesson)).isEqualTo("DISABLED");verifyNoInteractions(lessonAgent);
    }
    java.nio.file.Path liveFixtures;
    boolean distinctLearningSeeds;
    @Test void learningFixturesPreserveEachDirectionsFirstFrame() throws Exception {
        distinctLearningSeeds=true;
        learningPair();
        assertThat(onlyLesson()).isNotNull();
        verifyNoInteractions(lessonAgent);
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="RUN_STYLED_LESSON_LIVE",matches="true")
    void liveLunaLearnsFromPreviouslyReviewedClipsAndPinsTheLesson() throws Exception {
        liveFixtures=java.nio.file.Path.of(System.getenv("STYLED_LESSON_FIXTURES"));
        when(quality.contract(any(),any())).thenReturn(json.valueToTree(Map.of("tailCarriage","UNKNOWN")));
        UUID first=learningPair();UUID lesson=onlyLesson();
        var props=new org.shelterconnect.api.chat.AiProperties(true,System.getenv("OPENAI_API_KEY"),System.getenv("OPENAI_MODEL"),60);
        var live=new StyledLessonAgent(new org.shelterconnect.api.chat.OpenAiResponsesClient(props,json),json);
        doAnswer(c->live.propose(c.getArgument(0),c.getArgument(1))).when(lessonAgent).propose(any(),any());
        doAnswer(c->live.replay(c.getArgument(0),c.getArgument(1),c.getArgument(2))).when(lessonAgent).replay(any(),any(),anyList());
        lessonWorker.tick();lessonWorker.tick();
        var report=(tools.jackson.databind.node.ObjectNode)lessonStore.read(opSubject,lesson);
        report.put("mode","real Luna proposal and blinded replay; local PostgreSQL; recorded sprites; PixelLab mocked");
        report.put("model",props.model());
        var destination=java.nio.file.Path.of(System.getenv("STYLED_LESSON_LIVE_REPORT"));
        java.nio.file.Files.createDirectories(destination.toAbsolutePath().getParent());
        java.nio.file.Files.writeString(destination,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        assertThat(lessonStatus(lesson)).isEqualTo("ACTIVE");
        nextLearningDog();UUID next=request();tick();tick();review(next,true,"APPROVE",200);
        clearInvocations(provider,quality);finish(next);
        var pinned=json.readTree(jdbc.queryForObject("SELECT learned_lessons::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='sit-west'",String.class,next));
        assertThat(pinned.get(0).path("id").asText()).isEqualTo(lesson.toString());
        var payloads=org.mockito.ArgumentCaptor.forClass(JsonNode.class);
        verify(provider,atLeastOnce()).submit(eq(false),payloads.capture());
        assertThat(payloads.getAllValues().stream().anyMatch(p->p.path("description").asText()
            .contains(pinned.get(0).path("prevention").asText()))).isTrue();
        verify(quality).review(any(),anyList(),anyList(),eq("SIT"),eq("west"),eq(pinned));
        report.set("nextGenerationSnapshot",pinned);report.put("nextPayloadVerified",true);
        java.nio.file.Files.writeString(destination,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
    String currentRules(){try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("asset-styles/cozy32-v1/quality-rules.json"))));}catch(Exception e){throw new AssertionError(e);}}
    JsonNode recoveryVerdict(boolean passed) {
        return json.valueToTree(Map.of("passed",passed,"issues",passed?List.of():List.of("IDENTITY_DRIFT"),
            "rulesSha256",currentRules(),"note","Known fixture verdict; not a live quality approval"));
    }
    UUID learningRecoveryJob(boolean automatic) throws Exception {
        // Three approved colors keep raw and restored fixture pixels meaningful.
        var seed=ImageIO.read(new ByteArrayInputStream(png));seed.setRGB(10,10,0xffeeddcc);seed.setRGB(11,10,0xff222222);
        var bytes=new ByteArrayOutputStream();ImageIO.write(seed,"png",bytes);png=bytes.toByteArray();
        var changed=ImageIO.read(new ByteArrayInputStream(png));changed.setRGB(13,15,0xffeeddcc);
        bytes=new ByteArrayOutputStream();ImageIO.write(changed,"png",bytes);byte[] bad=bytes.toByteArray();
        when(provider.poll(any(),eq(false))).thenAnswer(c->{
            var frames=new ArrayList<String>();frames.add(Base64.getEncoder().encodeToString(png));
            for(int i=1;i<9;i++)frames.add(Base64.getEncoder().encodeToString(bad));
            return json.valueToTree(Map.of("status","COMPLETED","frames",frames));
        });
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("south"))).thenAnswer(c->{
            outsideTransaction();var frames=c.<List<byte[]>>getArgument(2);
            return recoveryVerdict(frames.stream().allMatch(f->Arrays.equals(f,frames.getFirst())));
        });
        when(codec.seedIdle(anyString(),any(),anyInt())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("description","Keep the approved dog calm and stationary."));});
        when(provider.editAnimation(any())).thenAnswer(c->{outsideTransaction();return UUID.randomUUID();});
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("south"),any())).thenAnswer(c->{outsideTransaction();return recoveryVerdict(true);});
        when(lessonAgent.propose(any(),any())).thenReturn(json.valueToTree(Map.of("prevention","Keep the approved chest markings unchanged throughout every frame.","criterion","The approved chest markings change color between idle frames.")));
        when(lessonAgent.replay(any(),any(),anyList())).thenAnswer(c->replayAnswer(c.getArgument(2),false));
        UUID id=request();if(!automatic)jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=quality_policy-'learningRecovery' WHERE id=?",id);
        tick();tick();review(id,true,"APPROVE",200);finish(id);
        assertThat(read(id).path("failureCode").asText()).isEqualTo("QUALITY_REPAIR_EXHAUSTED");return id;
    }
    void recoveryTick(){jdbc.update("UPDATE shelter.styled_learning_recoveries SET next_run_at=now() WHERE job_id IN (SELECT id FROM shelter.asset_jobs WHERE dog_id=?)",dog);recoveryWorker.tick();}
    JsonNode recoveryState(UUID id)throws Exception {return step(id,"idle-south").path("learningRecovery");}
    Map<String,Object> learningRequest(UUID id)throws Exception {
        return Map.of("requestId",UUID.randomUUID(),"note","Enable one learned repair with exact stored hashes and preserve all existing attempts",
            "expectedSeedHashes",read(id).at("/steps/0/result/hashes"),"expectedSheetHashes",Map.of("idle-south",step(id,"idle-south").at("/result/sha256").asText()));
    }
    @Test void recoveryBuildsCheckedReferenceLearnsAndAutomaticallyAppliesOneNewRule()throws Exception {
        UUID id=learningRecoveryJob(true);var original=step(id,"idle-south").path("result").deepCopy();
        assertThat(recoveryState(id).path("state").asText()).isEqualTo("WAITING_EVIDENCE");
        clearInvocations(provider);recoveryTick();
        assertThat(recoveryState(id).path("state").asText()).isEqualTo("WAITING_RULE");
        assertThat(recoveryState(id).at("/referenceReport/evidenceKind").asText()).isEqualTo("APPROVED_IDLE_REFERENCE");
        assertThat(step(id,"idle-south").path("result")).isEqualTo(original);verifyNoInteractions(provider);
        lessonWorker.tick();lessonWorker.tick();assertThat(lessonStatus(onlyLesson())).isEqualTo("ACTIVE");
        recoveryTick();assertThat(recoveryState(id).path("state").asText()).isEqualTo("QUEUED");
        tick();tick();finish(id);
        var calls=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(provider,times(1)).editAnimation(calls.capture());
        assertThat(calls.getValue().path("description").asText()).contains("Keep the approved chest markings unchanged");
        assertThat(recoveryState(id).path("state").asText()).isEqualTo("COMPLETED");
        assertThat(step(id,"idle-south").path("repairCount").asInt()).isEqualTo(3);
        assertThat(step(id,"idle-south").at("/qualityReport/learnedLessons/0/id").asText()).isEqualTo(onlyLesson().toString());
        for(int i=0;i<3;i++)recoveryTick();verify(provider,times(1)).editAnimation(any());
        assertThat(jdbc.queryForObject("SELECT attempt_history::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-south'",String.class,id)).contains(original.path("sha256").asText(),"learningRecovery");
    }
    @Test void rejectedReferenceNeverBecomesPositiveOrBuysAnExtraAttempt()throws Exception {
        UUID id=learningRecoveryJob(true);when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("south"))).thenReturn(recoveryVerdict(false));
        clearInvocations(provider,quality);recoveryTick();recoveryTick();
        assertThat(recoveryState(id).path("state").asText()).isEqualTo("NEEDS_REVIEW");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=? AND passed AND action='IDLE' AND direction='south'",Integer.class,id)).isZero();
        verify(quality,times(1)).review(any(),anyList(),anyList(),eq("IDLE"),eq("south"));verifyNoInteractions(provider);
    }
    @Test void failedRuleReplayKeepsRecoveryWaitingWithoutProviderCalls()throws Exception {
        UUID id=learningRecoveryJob(true);recoveryTick();when(lessonAgent.replay(any(),any(),anyList())).thenAnswer(c->replayAnswer(c.getArgument(2),true));
        lessonWorker.tick();lessonWorker.tick();assertThat(lessonStatus(onlyLesson())).isEqualTo("REJECTED");
        clearInvocations(provider);recoveryTick();assertThat(recoveryState(id).path("state").asText()).isEqualTo("WAITING_RULE");verifyNoInteractions(provider);
    }
    @Test void rejectedCandidateIsRewrittenWithFeedbackThenIndependentlyReplayed()throws Exception {
        UUID id=learningRecoveryJob(true);
        jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=quality_policy || '{\"lessonRevision\":\"validated-rule-rewrite-v1\"}'::jsonb WHERE id=?",id);
        recoveryTick();when(lessonAgent.replay(any(),any(),anyList())).thenAnswer(c->replayAnswer(c.getArgument(2),true));
        lessonWorker.tick();lessonWorker.tick();assertThat(lessonStatus(onlyLesson())).isEqualTo("WAITING_EVIDENCE");
        var record=lessonStore.read(opSubject,onlyLesson());assertThat(record.path("revisionCount").asInt()).isEqualTo(1);
        assertThat(record.at("/revisionFeedback/failedChecks").toString()).contains("REJECTS_KNOWN_GOOD_EXAMPLE");
        assertThat(record.path("events").toString()).contains("REJECTED","candidateSha256","REVISION_QUEUED");
        clearInvocations(provider,lessonAgent);
        when(lessonAgent.replay(any(),any(),anyList())).thenAnswer(c->replayAnswer(c.getArgument(2),false));
        lessonWorker.tick();lessonWorker.tick();assertThat(lessonStatus(onlyLesson())).isEqualTo("ACTIVE");
        var proposal=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(lessonAgent).propose(proposal.capture(),any());
        assertThat(proposal.getValue().path("revisionFeedback").toString()).contains("REJECTS_KNOWN_GOOD_EXAMPLE");
        var replay=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(lessonAgent).replay(replay.capture(),any(),anyList());
        assertThat(replay.getValue().has("revisionFeedback")).isFalse();verifyNoInteractions(provider);
        recoveryTick();tick();tick();finish(id);assertThat(recoveryState(id).path("state").asText()).isEqualTo("COMPLETED");
    }
    @Test void repeatedBadRulesStopAfterTwoRewritesWithoutSpendingOnImages()throws Exception {
        UUID id=learningRecoveryJob(true);
        jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=quality_policy || '{\"lessonRevision\":\"validated-rule-rewrite-v1\"}'::jsonb WHERE id=?",id);
        recoveryTick();when(lessonAgent.replay(any(),any(),anyList())).thenAnswer(c->replayAnswer(c.getArgument(2),true));
        clearInvocations(provider,lessonAgent);
        for(int i=0;i<10;i++)lessonWorker.tick();
        assertThat(lessonStatus(onlyLesson())).isEqualTo("REJECTED");
        assertThat(lessonStore.read(opSubject,onlyLesson()).path("revisionCount").asInt()).isEqualTo(2);
        verify(lessonAgent,times(3)).propose(any(),any());verify(lessonAgent,times(3)).replay(any(),any(),anyList());
        recoveryTick();verifyNoInteractions(provider);
    }
    @org.junit.jupiter.params.ParameterizedTest @org.junit.jupiter.params.provider.CsvSource({"WALK,false","SIT,false","WALK,true","SIT,true"})
    void walkingAndSittingLearnFromRealMotionFramesAndContinueExactlyOnce(String action,boolean historical)throws Exception {
        var changed=ImageIO.read(new ByteArrayInputStream(png));changed.setRGB(12,12,0xff00ff00);
        var out=new ByteArrayOutputStream();ImageIO.write(changed,"png",out);byte[] raw=out.toByteArray();
        var edited=new java.util.concurrent.atomic.AtomicBoolean();
        when(provider.poll(any(),eq(false))).thenAnswer(c->json.valueToTree(Map.of("status","COMPLETED","frames",
            java.util.stream.IntStream.range(0,9).mapToObj(i->Base64.getEncoder().encodeToString(i==0 || !edited.get()?png:raw)).toList())));
        when(quality.review(any(),anyList(),anyList(),eq(action),eq("west"))).thenAnswer(c->{
            boolean pass=ImageIO.read(new ByteArrayInputStream(c.<List<byte[]>>getArgument(2).get(1))).getRGB(12,12)==0xff00ff00;
            return json.valueToTree(Map.of("passed",pass,"issues",pass?List.of():List.of("CANVAS_CLIPPING"),"rulesSha256",currentRules()));
        });
        when(codec.marginEdit(eq(action),eq("west"),any(),anyInt())).thenAnswer(c->{
            assertThat(sheetFrames(c.getArgument(2))).hasSize(9);return json.valueToTree(Map.of("description","Repair the complete motion while keeping all nine original frames."));
        });
        when(provider.editAnimation(any())).thenAnswer(c->{edited.set(true);return UUID.randomUUID();});
        when(lessonAgent.propose(any(),any())).thenReturn(json.valueToTree(Map.of("prevention",learnedPrevention(),"criterion","The tail tip touches the canvas boundary during the requested motion.")));
        when(lessonAgent.replay(any(),any(),anyList())).thenAnswer(c->replayAnswer(c.getArgument(2),false));
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);String label=action.toLowerCase()+"-west";
        assertThat(step(id,label).at("/learningRecovery/state").asText()).isEqualTo("WAITING_EVIDENCE");
        assertThat(step(id,label).at("/qualityReport/rawEditReview/passed").asBoolean()).isTrue();
        var original=step(id,label).path("result");
        if(historical) {
            jdbc.update("UPDATE shelter.styled_quality_examples SET rules_sha256=? WHERE job_id=? AND label=? AND passed","0".repeat(64),id,label);
            jdbc.update("UPDATE shelter.styled_asset_steps SET quality_report=jsonb_set(quality_report,'{rawEditReview,passed}','false') WHERE job_id=? AND label=?",id,label);
        }
        clearInvocations(provider,codec);
        recoveryTick();
        if(historical)assertThat(step(id,label).at("/learningRecovery/referenceReport/evidenceKind").asText()).isEqualTo("REVALIDATED_HISTORICAL_MOTION");verifyNoInteractions(provider);verify(codec,never()).seedIdle(anyString(),any(),anyInt());
        lessonWorker.tick();lessonWorker.tick();assertThat(lessonStatus(onlyLesson())).isEqualTo("ACTIVE");
        recoveryTick();assertThat(step(id,label).at("/learningRecovery/state").asText()).isEqualTo("QUEUED");
        when(quality.review(any(),anyList(),anyList(),eq(action),eq("west"),any())).thenReturn(json.valueToTree(Map.of("passed",true,"issues",List.of(),"rulesSha256",currentRules())));
        tick();tick();finish(id);
        assertThat(step(id,label).at("/learningRecovery/state").asText()).isEqualTo("COMPLETED");
        assertThat(step(id,label).path("repairCount").asInt()).isEqualTo(3);
        verify(codec).motion(any(),eq(action),eq("west"),any(),any());
        verify(codec,never()).marginEdit(anyString(),anyString(),any(),anyInt());
        verify(codec,never()).seedIdle(anyString(),any(),anyInt());verify(provider,times(1)).submit(eq(false),any());verify(provider,never()).editAnimation(any());
        assertThat(jdbc.queryForObject("SELECT attempt_history::text FROM shelter.styled_asset_steps WHERE job_id=? AND label=?",String.class,id,label)).contains(original.path("sha256").asText());
        for(int i=0;i<3;i++)recoveryTick();verify(provider,times(1)).submit(eq(false),any());
    }
    @Test void failedWalkingWithoutPositiveMotionNeverUsesStandingReference()throws Exception {
        when(quality.review(any(),anyList(),anyList(),eq("WALK"),eq("west"))).thenReturn(json.valueToTree(Map.of("passed",false,"issues",List.of("ACTION_MISSING"),"rulesSha256",currentRules())));
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);
        clearInvocations(provider,storage,quality);recoveryTick();
        assertThat(step(id,"walk-west").at("/learningRecovery/reason").asText()).isEqualTo("NO_APPROVED_MOTION_REFERENCE");
        verifyNoInteractions(provider,storage,quality);
    }
    @Test void lostOrUnappliedLearnedRuleCannotBuyAnUninformedRepair()throws Exception {
        UUID id=learningRecoveryJob(true);recoveryTick();lessonWorker.tick();lessonWorker.tick();recoveryTick();
        lessonStore.disable(opSubject,onlyLesson(),json.valueToTree(Map.of("note","Disable the rule before paid submission")));
        clearInvocations(provider);tick();
        assertThat(read(id).path("failureCode").asText()).isEqualTo("LEARNED_RULE_NOT_APPLIED");
        assertThat(recoveryState(id).path("state").asText()).isEqualTo("FAILED");verifyNoInteractions(provider);
    }
    @Test void failedLearnedRepairPreservesLimitAndNeverLoops()throws Exception {
        UUID id=learningRecoveryJob(true);recoveryTick();lessonWorker.tick();lessonWorker.tick();recoveryTick();
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("south"),any())).thenReturn(recoveryVerdict(false));
        clearInvocations(provider);tick();tick();finish(id);recoveryTick();
        assertThat(recoveryState(id).path("state").asText()).isEqualTo("EXHAUSTED");
        assertThat(read(id).path("failureCode").asText()).isEqualTo("QUALITY_REPAIR_EXHAUSTED");
        verify(provider,times(1)).editAnimation(any());review(id,false,"APPROVE",409);
    }
    @Test void learnedAttemptStorageFailureCanResumeWithoutBuyingAnotherImage()throws Exception {
        UUID id=learningRecoveryJob(true);recoveryTick();lessonWorker.tick();lessonWorker.tick();recoveryTick();
        var failed=new java.util.concurrent.atomic.AtomicBoolean();
        doAnswer(c->{if(c.<String>getArgument(0).contains("raw-edits/idle-south-3") && !failed.getAndSet(true))throw new RuntimeException("Disposable storage failure");objects.put(c.getArgument(0),c.getArgument(1));return null;}).when(storage).put(anyString(),any());
        clearInvocations(provider);tick();tick();assertThat(read(id).path("status").asText()).isEqualTo("FAILED");
        post(opSubject,"/v1/operations/styled-asset-jobs/"+id+"/recover",Map.of(),200);
        assertThat(recoveryState(id).path("state").asText()).isEqualTo("QUEUED");
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("south"),any())).thenReturn(recoveryVerdict(true));
        finish(id);assertThat(recoveryState(id).path("state").asText()).isEqualTo("COMPLETED");verify(provider,times(1)).editAnimation(any());
    }
    @Test void failedLearnedProviderJobCannotBeRecoveredIntoAnotherPaidSubmission()throws Exception {
        UUID id=learningRecoveryJob(true);recoveryTick();lessonWorker.tick();lessonWorker.tick();recoveryTick();
        when(provider.poll(any(),eq(false))).thenReturn(json.valueToTree(Map.of("status","FAILED")));
        clearInvocations(provider);tick();tick();
        post(opSubject,"/v1/operations/styled-asset-jobs/"+id+"/recover",Map.of(),409);
        tick();verify(provider,times(1)).editAnimation(any());
    }
    @Test void existingPackRuleRecheckDoesNotOptIntoAutomaticLearning()throws Exception {
        UUID id=learningRecoveryJob(false);var request=seedRecheckBody(id);
        post(subject,path(id)+"/quality-recheck",request,200);
        assertThat(read(id).path("qualityPolicy").has("learningRecovery")).isFalse();
    }
    @Test void existingPacksRequireExplicitConsentAndReplayCannotResetLearning()throws Exception {
        UUID id=learningRecoveryJob(false);assertThat(recoveryState(id).isNull()).isTrue();
        clearInvocations(provider);recoveryTick();verifyNoInteractions(provider);var request=learningRequest(id);
        post(null,path(id)+"/learning-repair",request,401);post(UUID.randomUUID(),path(id)+"/learning-repair",request,403);
        post(subject,path(id)+"/learning-repair",request,200);post(subject,path(id)+"/learning-repair",request,200);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_learning_recoveries WHERE job_id=?",Integer.class,id)).isEqualTo(1);
        var changed=new HashMap<>(request);changed.put("note","A different meaning with a reused request id must not be accepted");
        post(subject,path(id)+"/learning-repair",changed,409);
        post(subject,path(id)+"/learning-repair",learningRequest(id),409);
        assertThat(step(id,"idle-south").path("repairCount").asInt()).isEqualTo(2);verifyNoInteractions(provider);
    }
    @Test void sourceRevocationStopsRecoveryBeforeAnyUpload()throws Exception {
        UUID id=learningRecoveryJob(true);jdbc.update("UPDATE shelter.asset_source_permissions SET revoked_at=now() WHERE id=?",permission);
        clearInvocations(storage,quality,provider);recoveryTick();
        assertThat(recoveryState(id).path("state").asText()).isEqualTo("DISABLED");verifyNoInteractions(storage,quality,provider);
    }
    @Test void interruptedReferenceReviewDoesNotRepeatTheModelCall()throws Exception {
        UUID id=learningRecoveryJob(true);jdbc.update("UPDATE shelter.styled_learning_recoveries SET state='CHECKING_REFERENCE',lease_until=now()-interval '1 minute' WHERE job_id=?",id);
        clearInvocations(storage,quality,provider);recoveryTick();assertThat(recoveryState(id).path("state").asText()).isEqualTo("FAILED");verifyNoInteractions(storage,quality,provider);
    }
    @Test void rawPositiveIsKeptWhenTheRestoredImageFails()throws Exception {
        var changed=ImageIO.read(new ByteArrayInputStream(png));changed.setRGB(12,12,0xff00ff00);
        var out=new ByteArrayOutputStream();ImageIO.write(changed,"png",out);byte[] raw=out.toByteArray();
        var edited=new java.util.concurrent.atomic.AtomicBoolean();
        when(provider.poll(any(),eq(false))).thenAnswer(c->json.valueToTree(Map.of("status","COMPLETED","frames",
            java.util.stream.IntStream.range(0,9).mapToObj(i->Base64.getEncoder().encodeToString(i==0 || !edited.get()?png:raw)).toList())));
        var count=new java.util.concurrent.atomic.AtomicInteger();
        when(quality.review(any(),anyList(),anyList(),eq("SIT"),eq("west"))).thenAnswer(c->{boolean passed=count.incrementAndGet()>1 && ImageIO.read(new ByteArrayInputStream(c.<List<byte[]>>getArgument(2).get(1))).getRGB(12,12)==0xff00ff00;
            return json.valueToTree(Map.of("passed",passed,"issues",passed?List.of():List.of("CANVAS_CLIPPING"),"rulesSha256",currentRules()));});
        when(codec.marginEdit(anyString(),anyString(),any(),anyInt())).thenReturn(json.valueToTree(Map.of("description","repair whole strip")));
        when(provider.editAnimation(any())).thenAnswer(c->{edited.set(true);return UUID.randomUUID();});
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);var step=step(id,"sit-west");
        assertThat(step.at("/qualityReport/rawEditReview/passed").asBoolean()).isTrue();
        assertThat(step.at("/qualityReport/restoredReview/passed").asBoolean()).isFalse();
        assertThat(step.at("/qualityReport/passed").asBoolean()).isFalse();
        assertThat(step.at("/result/rawEdit/sha256")).isNotEqualTo(step.at("/result/sha256"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=? AND label='sit-west' AND passed AND input_sha256=?",Integer.class,id,step.at("/result/rawEdit/sha256").asText())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=? AND label='sit-west' AND NOT passed AND input_sha256=?",Integer.class,id,step.at("/result/sha256").asText())).isEqualTo(1);
        review(id,false,"APPROVE",409);
    }
    @Test void changedSeedQualityOrRulesPreventAnyLearningUpload()throws Exception {
        UUID id=learningRecoveryJob(true);
        jdbc.update("UPDATE shelter.styled_asset_steps SET quality_report=jsonb_set(quality_report,'{passed}','false') WHERE job_id=? AND label='character'",id);
        clearInvocations(storage,quality,provider);recoveryTick();
        assertThat(recoveryState(id).path("reason").asText()).isEqualTo("SEED_QUALITY_REVIEW_REQUIRED");verifyNoInteractions(storage,quality,provider);
    }
    @Test void changedRulesMakeRecoveryStaleBeforeAnyLearningUpload()throws Exception {
        UUID id=learningRecoveryJob(true);jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=jsonb_set(quality_policy,'{rulesSha256}',to_jsonb(CAST(? AS text))) WHERE id=?","0".repeat(64),id);
        clearInvocations(storage,quality,provider);recoveryTick();assertThat(recoveryState(id).path("state").asText()).isEqualTo("STALE");verifyNoInteractions(storage,quality,provider);
    }
    @Test void concurrentRecoveryTicksReviewTheReferenceOnlyOnce()throws Exception {
        UUID id=learningRecoveryJob(true);clearInvocations(quality,provider);var pool=Executors.newFixedThreadPool(2);
        try{var a=pool.submit(()->recoveryWorker.tick());var b=pool.submit(()->recoveryWorker.tick());a.get();b.get();}finally{pool.shutdownNow();}
        assertThat(recoveryState(id).path("state").asText()).isEqualTo("WAITING_RULE");
        verify(quality,times(1)).review(any(),anyList(),anyList(),eq("IDLE"),eq("south"));verifyNoInteractions(provider);
    }
    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="RUN_LEARNING_RECOVERY_LIVE",matches="true")
    void liveLunaRecoversB60EvidenceAndPinsAValidatedRule()throws Exception {
        // Recorded authorized B-60 pixels and verdicts; all persistence and provider submissions remain local/mocked.
        var fixtures=java.nio.file.Path.of(System.getenv("LEARNING_RECOVERY_FIXTURES"));
        var saved=json.readTree(java.nio.file.Files.readString(fixtures.resolve("job.json")));
        UUID id=learningRecoveryJob(false);
        jdbc.update("DELETE FROM shelter.styled_quality_lessons WHERE source_job_id=?",id);
        jdbc.update("DELETE FROM shelter.styled_quality_examples WHERE job_id=?",id);
        var base=json.createObjectNode();var keys=json.createObjectNode();var hashes=json.createObjectNode();
        String prefix=dog+"/"+id+"/native-32/";
        for(String d:List.of("south","north","west","east")) {
            byte[] data=java.nio.file.Files.readAllBytes(fixtures.resolve("directions/"+d+".png"));String key=prefix+"directions/"+d+".png";
            objects.put(key,data);keys.put(d,key);hashes.put(d,hex(data));
            assertThat(hashes.path(d)).isEqualTo(saved.at("/steps/0/result/hashes/"+d));
        }
        base.set("keys",keys);base.set("hashes",hashes);
        var policy=saved.path("qualityPolicy").deepCopy();
        jdbc.update("UPDATE shelter.styled_asset_steps SET result=?::jsonb,quality_report=NULL WHERE job_id=? AND label='character'",base.toString(),id);
        jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=?::jsonb,seed_review=jsonb_build_object('hashes',?::jsonb) WHERE id=?",policy.toString(),hashes.toString(),id);
        var old=saved.path("steps").valueStream().filter(n->n.path("label").asText().equals("idle-south")).findFirst().orElseThrow();
        var result=(tools.jackson.databind.node.ObjectNode)old.path("result").deepCopy();
        for(String source:List.of("sheets","raw-edits")) {
            byte[] data=java.nio.file.Files.readAllBytes(fixtures.resolve(source+"/idle-south.png"));String key=prefix+source+"/idle-south.png";objects.put(key,data);
            var target=source.equals("sheets")?result:(tools.jackson.databind.node.ObjectNode)result.path("rawEdit");
            assertThat(hex(data)).isEqualTo(target.path("sha256").asText());target.put("key",key);
        }
        var verdict=old.path("qualityReport");
        jdbc.update("UPDATE shelter.styled_asset_steps SET result=?::jsonb,quality_report=?::jsonb,repair_count=?,learned_lessons='[]' WHERE job_id=? AND label='idle-south'",result.toString(),verdict.toString(),old.path("repairCount").asInt(),id);
        UUID example=UUID.randomUUID();
        jdbc.update("INSERT INTO shelter.styled_quality_examples(id,job_id,label,action,direction,tail,rules_sha256,input_sha256,result,seeds,report,passed) VALUES (?,?,'idle-south','IDLE','south','UNKNOWN',?,?,?::jsonb,?::jsonb,?::jsonb,false)",example,id,currentRules(),result.path("sha256").asText(),result.toString(),base.toString(),verdict.toString());
        for(var issue:verdict.path("issues"))jdbc.update("INSERT INTO shelter.styled_quality_lessons(source_example_id,source_job_id,source_label,action,direction,tail,issue,rules_sha256) VALUES (?,?,'idle-south','IDLE','south','UNKNOWN',?,?)",example,id,issue.asText(),currentRules());
        post(subject,path(id)+"/learning-repair",learningRequest(id),200);
        var props=new org.shelterconnect.api.chat.AiProperties(true,System.getenv("OPENAI_API_KEY"),System.getenv("OPENAI_MODEL"),60);
        var client=new org.shelterconnect.api.chat.OpenAiResponsesClient(props,json);
        var realQuality=new StyledQualityAgent(client,props,json);var realLesson=new StyledLessonAgent(client,json);
        doAnswer(c->realQuality.review(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3),c.getArgument(4))).when(quality).review(any(),anyList(),anyList(),eq("IDLE"),eq("south"));
        doAnswer(c->realLesson.propose(c.getArgument(0),c.getArgument(1))).when(lessonAgent).propose(any(),any());
        doAnswer(c->realLesson.replay(c.getArgument(0),c.getArgument(1),c.getArgument(2))).when(lessonAgent).replay(any(),any(),anyList());
        clearInvocations(provider);recoveryTick();
        for(int i=0;i<4;i++)lessonWorker.tick();
        var report=json.createObjectNode();report.put("scope","Recorded B-60 images, real Luna calls, disposable local DB, mocked PixelLab only");
        report.put("rulesSha256",currentRules());report.set("recovery",recoveryState(id));
        report.set("lessons",json.valueToTree(jdbc.queryForList("SELECT status,issue,candidate::text,validation::text FROM shelter.styled_quality_lessons WHERE source_job_id=? ORDER BY issue",id)));
        report.set("events",json.valueToTree(jdbc.queryForList("SELECT e.event,e.detail::text FROM shelter.styled_quality_lesson_events e JOIN shelter.styled_quality_lessons l ON l.id=e.lesson_id WHERE l.source_job_id=? ORDER BY e.created_at,e.id",id)));
        var destination=java.nio.file.Path.of(System.getenv("LEARNING_RECOVERY_REPORT"));java.nio.file.Files.createDirectories(destination.getParent());
        java.nio.file.Files.writeString(destination,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
        verifyNoInteractions(provider);assertThat(recoveryState(id).at("/referenceReport/passed").asBoolean()).isTrue();
        recoveryTick();assertThat(recoveryState(id).path("state").asText()).isEqualTo("QUEUED");
        var realCodec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        doAnswer(c->realCodec.seedIdle(c.getArgument(0),c.getArgument(1),c.getArgument(2))).when(codec).seedIdle(anyString(),any(),anyInt());
        tick();var calls=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(provider,times(1)).editAnimation(calls.capture());
        var pinned=json.readTree(jdbc.queryForObject("SELECT learned_lessons::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-south'",String.class,id));
        assertThat(pinned.isEmpty()).isFalse();for(var rule:pinned)assertThat(calls.getValue().path("description").asText()).contains(rule.path("prevention").asText());
        report.set("pinnedRules",pinned);report.put("providerMock",true);report.put("outboundPayloadIncludesRules",true);
        java.nio.file.Files.writeString(destination,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
    String hex(byte[] bytes)throws Exception{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    String learnedPrevention(){return "Keep the entire seated tail tip tucked beside the hind paw, away from the canvas edge.";}
    JsonNode replayAnswer(List<StyledLessonAgent.Case> cases,boolean falsePositive) {
        return json.valueToTree(Map.of("safeAndGeneral",true,"reason","Offline wiring replay, not live model accuracy", "cases",cases.stream().map(c->{
            boolean bad=falsePositive || !c.report().path("passed").asBoolean();
            return Map.of("key",c.key(),"violates",bad,"frames",bad?List.of(3,4,5,6,7,8):List.of());}).toList()));
    }
    UUID onlyLesson(){return jdbc.queryForObject("SELECT id FROM shelter.styled_quality_lessons WHERE source_job_id IN (SELECT id FROM shelter.asset_jobs WHERE dog_id=?)",UUID.class,dog);}
    String lessonStatus(UUID id){return jdbc.queryForObject("SELECT status FROM shelter.styled_quality_lessons WHERE id=?",String.class,id);}
    UUID learningPair() throws Exception {
        var fixture=json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("scripts/fixtures/sit-tail-alpha.json")));
        var original=alphaFrames(fixture.at("/clips/original/frames"));var corrected=alphaFrames(fixture.at("/clips/corrected/frames"));
        // Distinct west and non-west seeds catch accidental reuse of the SIT source for every direction.
        List<byte[]> seedImages=distinctLearningSeeds?List.of(png,png,original.getFirst(),png):Collections.nCopies(4,original.getFirst());
        if(liveFixtures!=null) {
            original=sheetFrames(java.nio.file.Files.readAllBytes(liveFixtures.resolve("sheets/sit-left.png")));
            corrected=sheetFrames(java.nio.file.Files.readAllBytes(liveFixtures.resolve("sit-left-adjustment/sit-left.png")));
            seedImages=new ArrayList<>();for(String d:List.of("south","north","west","east"))seedImages.add(java.nio.file.Files.readAllBytes(liveFixtures.resolve("directions/"+d+".png")));
        }
        final var badFrames=original;final var goodFrames=corrected;
        var source=new java.util.concurrent.atomic.AtomicReference<>(original.getFirst());
        var motion=new java.util.concurrent.atomic.AtomicReference<>("BASE");
        var attempts=new java.util.concurrent.atomic.AtomicInteger();var checks=new java.util.concurrent.atomic.AtomicInteger();
        var realCodec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        doAnswer(c->{outsideTransaction();
            source.set(c.getArgument(3));
            motion.set(c.<String>getArgument(1)+"-"+c.<String>getArgument(2));return realCodec.motion(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3),c.getArgument(4));}).when(codec).motion(any(),anyString(),anyString(),any(),any());
        when(provider.poll(any(),eq(true))).thenReturn(json.valueToTree(Map.of("status","COMPLETED","directions",Map.of(
            "south",Base64.getEncoder().encodeToString(seedImages.get(0)),"north",Base64.getEncoder().encodeToString(seedImages.get(1)),
            "west",Base64.getEncoder().encodeToString(seedImages.get(2)),"east",Base64.getEncoder().encodeToString(seedImages.get(3))))));
        when(provider.poll(any(),eq(false))).thenAnswer(c->{outsideTransaction();var frames=motion.get().equals("SIT-west")?
            (attempts.getAndIncrement()==0?badFrames:goodFrames):Collections.nCopies(9,source.get());
            return json.valueToTree(Map.of("status","COMPLETED","frames",frames.stream().map(Base64.getEncoder()::encodeToString).toList()));});
        when(quality.review(any(),anyList(),anyList(),eq("SIT"),eq("west"))).thenAnswer(c->{outsideTransaction();boolean passed=checks.getAndIncrement()>0;
            return json.valueToTree(Map.of("passed",passed,"issues",passed?List.of():List.of("CANVAS_CLIPPING"),"edgeFrames",passed?List.of():List.of(3,4,5,6,7,8),"note","Recorded tail edge replay"));});
        when(quality.review(any(),anyList(),anyList(),anyString(),anyString(),any())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("passed",true,"issues",List.of()));});
        when(lessonAgent.propose(any(),any())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("prevention",learnedPrevention(),"criterion","The seated tail tip crosses the right frame boundary during descent or final hold."));});
        when(lessonAgent.replay(any(),any(),anyList())).thenAnswer(c->{outsideTransaction();return replayAnswer(c.getArgument(2),false);});
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);
        var result=read(id);
        assertThat(result.path("status").asText()).as("Recorded fixture job: %s",result.path("failureCode")).isEqualTo("REVIEW");return id;
    }
    List<byte[]> sheetFrames(byte[] png)throws Exception {
        var sheet=ImageIO.read(new ByteArrayInputStream(png));assertThat(sheet.getWidth()).isEqualTo(288);assertThat(sheet.getHeight()).isEqualTo(32);
        var result=new ArrayList<byte[]>();for(int i=0;i<9;i++){var out=new ByteArrayOutputStream();ImageIO.write(sheet.getSubimage(i*32,0,32,32),"png",out);result.add(out.toByteArray());}return result;
    }
    List<byte[]> alphaFrames(JsonNode values)throws Exception {
        var frames=new ArrayList<byte[]>();for(var rows:values){var image=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB);
            for(int y=0;y<32;y++)for(int x=0;x<32;x++)if((Long.parseLong(rows.get(y).asText(),16)&(1L<<(31-x)))!=0)image.setRGB(x,y,0xff464646);
            var out=new ByteArrayOutputStream();ImageIO.write(image,"png",out);frames.add(out.toByteArray());}
        return frames;
    }
    void nextLearningDog() throws Exception {
        extraDogs.add(dog);dog=UUID.randomUUID();photo=UUID.randomUUID();
        jdbc.update("INSERT INTO shelter.dogs(id,shelter_id,name,avatar_key,is_public,adoption_status) VALUES (?,?,'다음 테스트 강아지','sample',false,'AVAILABLE')",dog,shelter);
        jdbc.update("INSERT INTO shelter.dog_photos(id,dog_id,storage_bucket,storage_key,sort_order,rights_status,rights_note,rights_confirmed_by,rights_confirmed_at) VALUES (?,?,'dog-photos',?,0,'GRANTED','local test only',?,now())",photo,dog,dog+"/source.png",op);
        post(opSubject,"/v1/operations/asset-imports",Map.of("photoId",photo,"permissionId",permission),200);
    }
    String sha(byte[] data) {try{return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));}catch(Exception e){throw new AssertionError(e);}}
    JsonNode automaticSeedReport(List<byte[]> images,boolean passed) {
        var r=(tools.jackson.databind.node.ObjectNode)seedReport(images,passed);
        r.put("identity",passed?"PASS":"UNCERTAIN");r.put("model","fixture-vision");r.put("reviewedAt",java.time.Instant.now().toString());r.put("photoSha256",sha(png));
        r.putArray("edgeDirections");var views=r.putArray("views");
        for(String d:List.of("south","north","west","east"))views.add(json.valueToTree(Map.of("direction",d,"readability",d.equals("north")?"NOT_VISIBLE":"PASS","style","PASS")));
        var tail=json.createObjectNode().put("version","tail-anatomy-tristate-v2").put("passed",true).put("decision","PASS").put("shortTailSupported",false)
            .put("model","fixture-vision").put("reviewedAt",java.time.Instant.now().toString());tail.putArray("failedDirections");tail.putArray("uncertainDirections");
        for(String field:List.of("inputSha256","photoSha256","rulesSha256"))tail.set(field,r.path(field));
        tail.set("geometry",json.valueToTree(Map.of("west",Map.of("edgeContact",false,"opaqueComponents",1,"branchVersion","rear-silhouette-branches-v2","rearBranchSupport",true),"east",Map.of("edgeContact",false,"opaqueComponents",1,"branchVersion","rear-silhouette-branches-v2","rearBranchSupport",true))));
        tail.set("observation",json.valueToTree(Map.of("photoTail","OBSCURED","photoEvidence","Synthetic hidden photo tail",
            "views",List.of(Map.of("direction","west","tail","COMPLETE_CONNECTED","visibleEvidence","Synthetic complete tail","attachment","CONNECTED","contour","DISTINCT","tip","VISIBLE"),
                Map.of("direction","east","tail","COMPLETE_CONNECTED","visibleEvidence","Synthetic complete tail","attachment","CONNECTED","contour","DISTINCT","tip","VISIBLE")))));
        r.set("tailEvidence",tail);
        return r;
    }
    JsonNode automaticMotionReport(boolean passed) {
        var r=json.createObjectNode().put("version",StyledQualityAgent.VERSION).put("passed",passed)
            .put("rulesSha256",currentRules()).put("model","fixture-vision").put("reviewedAt",java.time.Instant.now().toString());
        r.set("issues",json.valueToTree(passed?List.of():List.of("ACTION_MISSING")));
        for(String f:List.of("edgeFrames","silhouetteFrames","detachedFrames","idleMotionFrames"))r.putArray(f);
        return r;
    }
    Map<String,Object> automaticInput() {
        var traits=new HashMap<>((Map<String,Object>)input().get("traits"));traits.put("sourcePhotoSha256",sha(png));
        return Map.of("photoId",photo,"traits",traits);
    }
    UUID automaticRequest()throws Exception {
        when(seedQuality.review(any(),anyList())).thenAnswer(c->{outsideTransaction();return automaticSeedReport(c.getArgument(1),true);});
        when(quality.review(any(),anyList(),anyList(),anyString(),anyString())).thenAnswer(c->{outsideTransaction();return automaticMotionReport(true);});
        UUID id=UUID.fromString(post(subject,"/v1/shelter-admin/dogs/"+dog+"/styled-assets",automaticInput(),202).at("/data/id").asText());
        jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=(quality_policy-'seedTailEvidenceVersion'-'recoveryVersion'-'motionFrameSize'-'maxSeedRepairs') || '{\"seedMotionMargin\":2,\"maxRepairsPerClip\":2,\"seedEyeRepair\":\"native-seed-eye-inpaint-v1\",\"learningRecovery\":\"validated-motion-learning-v2\"}'::jsonb WHERE id=?",id);return id;
    }
    @Test void newJobsApproveSeedsAndCompletePackWithoutEitherHumanReviewCall()throws Exception {
        UUID id=automaticRequest();tick();tick();
        assertThat(read(id).path("status").asText()).isEqualTo("QUEUED");
        assertThat(read(id).at("/seedReview/actor").asText()).isEqualTo("SYSTEM");
        assertThat(read(id).at("/seedReview/hashes")).isEqualTo(read(id).at("/steps/0/result/hashes"));
        publicStatus(404);finish(id);
        var j=read(id);assertThat(j.path("status").asText()).isEqualTo("APPROVED");
        assertThat(j.at("/qualityApproval/actor").asText()).isEqualTo("SYSTEM");
        assertThat(j.at("/qualityApproval/steps").size()).isEqualTo(13);
        assertThat(j.at("/qualityApproval/actionPlan")).isEqualTo(j.path("actionPlan"));
        assertThat(jdbc.queryForObject("SELECT reviewed_by FROM shelter.asset_jobs WHERE id=?",UUID.class,id)).isNull();
        assertThat(jdbc.queryForObject("SELECT reviewed_at IS NOT NULL FROM shelter.asset_jobs WHERE id=?",Boolean.class,id)).isTrue();
        var manifest=get(null,"/v1/dogs/"+dog+"/assets",200).path("data");
        assertThat(manifest.at("/mapDirections/LEFT/WALK/frames").size()).isEqualTo(9);
        clearInvocations(provider);for(int i=0;i<3;i++)tick();publicStatus(200);
        var replay=post(subject,"/v1/shelter-admin/dogs/"+dog+"/styled-assets",automaticInput(),202).path("data");
        assertThat(replay.path("id").asText()).isEqualTo(id.toString());assertThat(replay.path("qualityApproval")).isEqualTo(j.path("qualityApproval"));
        verifyNoInteractions(provider);
    }
    @Test void newSeedWithTooLittleMotionClearanceStopsBeforeAnyAnimation()throws Exception {
        UUID id=automaticRequest();var directions=new LinkedHashMap<String,String>();
        for(String d:List.of("south","north","west","east"))directions.put(d,Base64.getEncoder().encodeToString(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("scripts/fixtures/oshu-motion-learning-v13/directions/"+d+".png"))));
        when(provider.poll(any(),eq(true))).thenReturn(json.valueToTree(Map.of("status","COMPLETED","directions",directions)));
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");
        assertThat(read(id).path("seedReview").isNull()).isTrue();
        assertThat(step(id,"character").at("/qualityReport/issues").toString()).contains("SEED_MOTION_MARGIN");
        assertThat(step(id,"character").path("repairCount").asInt()).isEqualTo(2);
        verify(provider,times(3)).submit(eq(true),any());verify(provider,never()).submit(eq(false),any());
    }
    UUID eyeRepairJob()throws Exception {
        UUID id=automaticRequest();var reviews=new java.util.concurrent.atomic.AtomicInteger();
        when(seedQuality.review(any(),anyList())).thenAnswer(c->{outsideTransaction();
            var report=(tools.jackson.databind.node.ObjectNode)automaticSeedReport(c.getArgument(1),true);
            if(reviews.getAndIncrement()==0){report.put("passed",false);report.set("issues",json.valueToTree(List.of("EYE_READABILITY")));
                for(var v:report.path("views"))if(v.path("direction").asText().equals("west"))((tools.jackson.databind.node.ObjectNode)v).put("readability","FAIL");}
            return report;
        });
        when(eyeAi.structuredImage(anyString(),anyString(),any(),anyMap())).thenAnswer(c->{outsideTransaction();return json.readTree("{\"confident\":true,\"note\":\"synthetic locator fixture\",\"regions\":[{\"direction\":\"west\",\"x\":12,\"y\":10,\"width\":3,\"height\":3}]}");});
        when(provider.editSeedEyes(any())).thenAnswer(c->{outsideTransaction();var p=c.<JsonNode>getArgument(0);
            var sheet=ImageIO.read(new ByteArrayInputStream(Base64.getDecoder().decode(p.at("/inpainting_image/image/base64").asText())));
            sheet.setRGB(64+13,11,0xffccbba0);var out=new ByteArrayOutputStream();ImageIO.write(sheet,"png",out);
            when(provider.pollSeedEyes(any())).thenAnswer(call->{outsideTransaction();return json.valueToTree(Map.of("status","COMPLETED","eyeSheet",Base64.getEncoder().encodeToString(out.toByteArray()),"usage",Map.of("type","fixture","generations",1)));});
            return UUID.randomUUID();
        });
        return id;
    }
    @Test void eyeOnlyRepairUsesArchivedPlanAfterCheckpointAndDoesNotBuyAnotherCharacter()throws Exception {
        UUID id=eyeRepairJob();tick();tick();
        assertThat(step(id,"character").path("repairCount").asInt()).isEqualTo(1);
        tick();
        doThrow(new RuntimeException("storage interrupted")).when(storage).put(contains("directions/repair-1/"),any());
        tick();assertThat(read(id).path("status").asText()).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("SELECT provider_result IS NOT NULL FROM shelter.styled_asset_steps WHERE job_id=? AND label='character'",Boolean.class,id)).isTrue();
        doAnswer(c->{outsideTransaction();objects.put(c.getArgument(0),c.getArgument(1));return null;}).when(storage).put(contains("directions/repair-1/"),any());
        jdbc.update("UPDATE shelter.styled_asset_steps SET status='PERSISTING',quality_report='{}'::jsonb WHERE job_id=? AND label='character'",id);
        jdbc.update("UPDATE shelter.asset_jobs SET status='RUNNING',failure_code=NULL WHERE id=?",id);
        tick();assertThat(read(id).path("status").asText()).isEqualTo("QUEUED");
        assertThat(step(id,"character").at("/result/eyeRepair/visiblePixelsChangedOutsideMask").asInt()).isZero();
        assertThat(step(id,"character").at("/result/providerUsage/generations").asInt()).isEqualTo(1);
        assertThat(step(id,"character").at("/result/eyeRepair/sourceHashes/north").asText()).isEqualTo(step(id,"character").at("/result/hashes/north").asText());
        verify(provider,times(1)).submit(eq(true),any());verify(provider,times(1)).editSeedEyes(any());verify(provider,times(1)).pollSeedEyes(any());
        verify(provider,never()).submit(eq(false),any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_submissions WHERE job_id=?",Integer.class,id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT attempt_history::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='character'",String.class,id)).contains("eyeRepairPlan","EYE_READABILITY","providerJobId");
    }
    @Test void eyeRepairUnknownPaidOutcomeNeverResubmits()throws Exception {
        UUID id=eyeRepairJob();tick();tick();
        doThrow(new RuntimeException("lost acknowledgement")).when(provider).editSeedEyes(any());
        tick();assertThat(read(id).path("status").asText()).isEqualTo("OUTCOME_UNKNOWN");
        for(int i=0;i<3;i++)tick();verify(provider,times(1)).editSeedEyes(any());verify(provider,times(1)).submit(eq(true),any());
        verify(provider,never()).submit(eq(false),any());publicStatus(404);
    }
    @Test void automaticApprovalPreservesDogShelterAndSourceAccessBoundaries()throws Exception {
        UUID id=automaticRequest();jdbc.update("UPDATE shelter.dogs SET is_public=false WHERE id=?",dog);finish(id);
        assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");publicStatus(404);
        jdbc.update("UPDATE shelter.dogs SET is_public=true WHERE id=?",dog);jdbc.update("UPDATE shelter.shelters SET is_public=false WHERE id=?",shelter);publicStatus(404);
        jdbc.update("UPDATE shelter.shelters SET is_public=true WHERE id=?",shelter);publicStatus(200);
        jdbc.update("UPDATE shelter.asset_source_permissions SET revoked_at=now() WHERE id=?",permission);publicStatus(409);
    }
    @Test void uncertainSeedRemainsAnExceptionWithoutGeneratingAnyMotion()throws Exception {
        UUID id=automaticRequest();when(seedQuality.review(any(),anyList())).thenAnswer(c->automaticSeedReport(c.getArgument(1),false));finish(id);
        assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");
        assertThat(read(id).path("seedReview").isNull()).isTrue();assertThat(read(id).path("qualityApproval").isNull()).isTrue();
        verify(provider,times(3)).submit(eq(true),any());verify(provider,never()).submit(eq(false),any());
        review(id,true,"APPROVE",409);review(id,true,"REJECT",200);tick();assertThat(read(id).path("status").asText()).isEqualTo("REJECTED");
    }
    @Test void failedMotionIsNotAutomaticallyPublishedAndKeepsBoundedRepairHistory()throws Exception {
        UUID id=automaticRequest();when(quality.review(any(),anyList(),anyList(),eq("WALK"),eq("west"))).thenAnswer(c->automaticMotionReport(false));finish(id);
        assertThat(read(id).path("status").asText()).isEqualTo("REVIEW");assertThat(read(id).path("failureCode").asText()).isEqualTo("QUALITY_REPAIR_EXHAUSTED");
        assertThat(step(id,"walk-west").path("repairCount").asInt()).isEqualTo(2);
        assertThat(read(id).path("qualityApproval").isNull()).isTrue();publicStatus(404);
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(attempt_history) FROM shelter.styled_asset_steps WHERE job_id=? AND label='walk-west'",Integer.class,id)).isEqualTo(2);
        // A later successful, byte-preserving recheck completes without another human approval.
        post(subject,path(id)+"/quality-recheck",seedRecheckBody(id),200);
        when(quality.review(any(),anyList(),anyList(),eq("WALK"),eq("west"))).thenAnswer(c->automaticMotionReport(true));
        clearInvocations(provider);finish(id);assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");verifyNoInteractions(provider);
    }
    @Test void sourceRevocationDuringLastReviewCancelsInsteadOfApproving()throws Exception {
        UUID id=automaticRequest();when(quality.review(any(),anyList(),anyList(),eq("SIT"),eq("east"))).thenAnswer(c->{
            jdbc.update("UPDATE shelter.asset_source_permissions SET revoked_at=now() WHERE id=?",permission);return automaticMotionReport(true);});
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("CANCELLED");assertThat(read(id).path("qualityApproval").isNull()).isTrue();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"missing-report","stale-rules","changed-image","wrong-direction","changed-seed","missing-lessons","uncertain-identity","raw-edit-unreviewed","changed-pinned-lessons","incomplete-plan"})
    void automaticApprovalRejectsIncompleteOrStaleEvidence(String defect)throws Exception {
        UUID id=automaticRequest();finish(id);assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
        jdbc.update("UPDATE shelter.asset_jobs SET status='QUEUED',quality_approval=NULL,reviewed_at=NULL WHERE id=?",id);
        String label=defect.equals("uncertain-identity")?"character":"walk-west";
        var result=(tools.jackson.databind.node.ObjectNode)step(id,label).path("result").deepCopy();
        var report=(tools.jackson.databind.node.ObjectNode)step(id,label).path("qualityReport").deepCopy();
        switch(defect) {
            case "missing-report" -> report.removeAll();
            case "stale-rules" -> report.put("rulesSha256","0".repeat(64));
            case "changed-image" -> result.put("sha256","0".repeat(64));
            case "wrong-direction" -> report.put("direction","east");
            case "changed-seed" -> report.putObject("seedHashes").put("west","0".repeat(64));
            case "missing-lessons" -> report.remove("learnedLessons");
            case "uncertain-identity" -> report.put("identity","UNCERTAIN");
            case "raw-edit-unreviewed" -> result.putObject("rawEdit").put("sha256","0".repeat(64));
            case "changed-pinned-lessons" -> jdbc.update("UPDATE shelter.styled_asset_steps SET learned_lessons='[{\"id\":\"unknown\",\"sha256\":\"unknown\"}]'::jsonb WHERE job_id=? AND label=?",id,label);
            case "incomplete-plan" -> jdbc.update("UPDATE shelter.asset_jobs SET action_plan=action_plan || '[\"RUN\"]'::jsonb WHERE id=?",id);
        }
        jdbc.update("UPDATE shelter.styled_asset_steps SET result=?::jsonb,quality_report=?::jsonb WHERE job_id=? AND label=?",result.toString(),report.toString(),id,label);
        clearInvocations(provider);tick();assertThat(read(id).path("status").asText()).isEqualTo(defect.equals("incomplete-plan")?"FAILED":"REVIEW");
        assertThat(read(id).path("qualityApproval").isNull()).isTrue();publicStatus(404);verifyNoInteractions(provider);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"model","reviewedAt","views","identity","edgeDirections","source-photo"})
    void baseApprovalRequiresCompleteReviewEvidence(String missing)throws Exception {
        UUID id=automaticRequest();
        when(seedQuality.review(any(),anyList())).thenAnswer(c->{
            var r=(tools.jackson.databind.node.ObjectNode)automaticSeedReport(c.getArgument(1),true);r.remove(missing);return r;});
        if(missing.equals("source-photo"))jdbc.update("UPDATE shelter.asset_jobs SET styled_input=jsonb_set(styled_input,'{sourcePhotoSha256}',to_jsonb(CAST(? AS text))) WHERE id=?","0".repeat(64),id);
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");
        assertThat(read(id).path("failureCode").asText()).isEqualTo("AUTO_APPROVAL_EVIDENCE_REQUIRED");
        assertThat(read(id).path("seedReview").isNull()).isTrue();verify(provider,never()).submit(eq(false),any());
    }
    @Test void recoveryOfCompletedWorkIsIdempotentAndDatabaseRequiresMachineEvidence()throws Exception {
        UUID id=automaticRequest();
        assertThatThrownBy(()->jdbc.update("UPDATE shelter.asset_jobs SET status='APPROVED',reviewed_at=now() WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE shelter.asset_jobs SET status='APPROVED',reviewed_at=now(),quality_approval='{}'::jsonb WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        finish(id);var evidence=read(id).path("qualityApproval");
        jdbc.update("UPDATE shelter.asset_jobs SET status='RUNNING',quality_approval=NULL,reviewed_at=NULL,lease_token=NULL,lease_until=NULL WHERE id=?",id);
        clearInvocations(provider);tick();assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");verifyNoInteractions(provider);
        assertThat(read(id).at("/qualityApproval/steps")).isEqualTo(evidence.path("steps"));
        assertThatThrownBy(()->jdbc.update("UPDATE shelter.asset_jobs SET quality_approval='{}'::jsonb WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=quality_policy-'automaticApproval' WHERE id=?",id)).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    UUID recoveryRequest(int failedBaseReviews,boolean failSouthWalk)throws Exception {
        var seedCalls=new java.util.concurrent.atomic.AtomicInteger();var edits=new java.util.concurrent.atomic.AtomicInteger();
        var motionCalls=new java.util.concurrent.atomic.AtomicInteger();var requests=new HashMap<UUID,JsonNode>();
        when(seedQuality.reviewRecovery(any(),anyList(),any(),any())).thenAnswer(c->{outsideTransaction();
            boolean passed=seedCalls.getAndIncrement()>=failedBaseReviews;var report=(tools.jackson.databind.node.ObjectNode)automaticSeedReport(c.getArgument(1),passed);
            report.put("recoveryVersion","photo-grounded-recovery-v1");report.put("appearance",passed?"PASS":"FAIL");report.put("repairDescription","Restore the photo white blaze while keeping the current shaded puppy style.");
            var views=json.createArrayNode();for(String d:List.of("south","north","west","east"))views.add(json.valueToTree(Map.of("direction",d,"confidence",.9,
                "identityMatches",passed || !d.equals("south"),"eyesReadable",true,"styleMatches",true,"directionCorrect",true,"tailPlausible",true,
                "issues",!passed && d.equals("south")?List.of("COAT_MISMATCH"):List.of())));
            report.set("propertyReview",json.valueToTree(Map.of("views",views,"tailConsistent",true)));return report;});
        when(provider.editSeeds(any())).thenAnswer(c->{outsideTransaction();return UUID.randomUUID();});
        when(provider.pollSeeds(any())).thenAnswer(c->{outsideTransaction();var im=ImageIO.read(new ByteArrayInputStream(png));im.setRGB(12,9,0xffaab000+edits.incrementAndGet());
            var out=new ByteArrayOutputStream();ImageIO.write(im,"png",out);String b=Base64.getEncoder().encodeToString(out.toByteArray());
            return json.valueToTree(Map.of("status","COMPLETED","directions",Map.of("south",b,"north",b,"west",b,"east",b)));});
        when(provider.pollSeeds(any(),anyList())).thenAnswer(c->{outsideTransaction();var im=ImageIO.read(new ByteArrayInputStream(png));im.setRGB(12,9,0xffaab000+edits.incrementAndGet());
            var out=new ByteArrayOutputStream();ImageIO.write(im,"png",out);String b=Base64.getEncoder().encodeToString(out.toByteArray());
            var dirs=new LinkedHashMap<String,String>();for(String d:c.<List<String>>getArgument(1))dirs.put(d,b);
            return json.valueToTree(Map.of("status","COMPLETED","directions",dirs));});
        doAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("first",Base64.getEncoder().encodeToString(c.getArgument(3)),"action",c.getArgument(1),"direction",c.getArgument(2)));}).when(codec).motion(any(),anyString(),anyString(),any(),any());
        when(provider.submit(eq(false),any())).thenAnswer(c->{outsideTransaction();UUID id=UUID.randomUUID();requests.put(id,c.getArgument(1));return id;});
        when(provider.editAnimation(any())).thenAnswer(c->{outsideTransaction();UUID id=UUID.randomUUID();requests.put(id,c.getArgument(0));return id;});
        when(provider.poll(any(),eq(false))).thenAnswer(c->{outsideTransaction();var req=requests.get(c.getArgument(0));
            var frames=req.has("frames")?req.path("frames").valueStream().map(f->f.at("/image/base64").asText()).toList():Collections.nCopies(9,req.path("first").asText());
            return json.valueToTree(Map.of("status","COMPLETED","frames",frames));});
        when(quality.review(any(),anyList(),anyList(),anyString(),anyString())).thenAnswer(c->{outsideTransaction();
            boolean passed=!(failSouthWalk && c.getArgument(3).equals("WALK") && c.getArgument(4).equals("south") && motionCalls.getAndIncrement()==0);
            var report=(tools.jackson.databind.node.ObjectNode)automaticMotionReport(passed);report.put("note",passed?"Synthetic valid verdict":"Frontal tail appears above the head");
            report.put("motionReviewVersion","motion-observation-tristate-v4").put("motionDecision",passed?"PASS":"CONFIRMED_DEFECT").put("referencePoseUsable",true);
            report.put("referenceFrameSha256",sha(c.<List<byte[]>>getArgument(1).get(List.of("south","north","west","east").indexOf(c.getArgument(4)))));
            report.set("reviewedFrameHashes",json.valueToTree(c.<List<byte[]>>getArgument(2).stream().map(this::sha).toList()));
            if(!passed)report.set("issues",json.valueToTree(List.of("TAIL_CARRIAGE")));return report;});
        return UUID.fromString(post(subject,"/v1/shelter-admin/dogs/"+dog+"/styled-assets",automaticInput(),202).at("/data/id").asText());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"missing","wrong-photo","false-pass"})
    void newJobsHoldMissingStaleOrContradictoryTailEvidenceBeforeMotion(String defect)throws Exception {
        UUID id=recoveryRequest(0,false);
        when(seedQuality.reviewRecovery(any(),anyList(),any(),any())).thenAnswer(c->{
            var r=(tools.jackson.databind.node.ObjectNode)automaticSeedReport(c.getArgument(1),true);
            r.put("recoveryVersion","photo-grounded-recovery-v1").put("appearance","PASS");
            if(defect.equals("missing"))r.remove("tailEvidence");
            else if(defect.equals("wrong-photo"))((tools.jackson.databind.node.ObjectNode)r.path("tailEvidence")).put("photoSha256","0".repeat(64));
            else ((tools.jackson.databind.node.ObjectNode)r.at("/tailEvidence/observation/views/1")).put("tail","NOT_DISCERNIBLE");
            return r;});
        finish(id);var j=read(id);
        assertThat(j.path("status").asText()).isEqualTo("SEED_REVIEW");assertThat(j.path("seedReview").isNull()).isTrue();
        assertThat(j.at("/qualityPolicy/seedTailEvidenceVersion").asText()).isEqualTo("tail-anatomy-tristate-v2");
        verify(provider,never()).submit(eq(false),any());verify(provider,never()).editSeeds(any());
    }
    @Test void uncertainMotionStopsPaidRepairsLearningAndFinalApproval()throws Exception {
        UUID id=recoveryRequest(0,false);
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("west"))).thenAnswer(c->{
            var r=(tools.jackson.databind.node.ObjectNode)automaticMotionReport(false);r.put("motionDecision","UNCERTAIN");
            r.set("issues",json.valueToTree(List.of("IDLE_MOTION")));return r;});
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("REVIEW");
        assertThat(read(id).path("failureCode").asText()).isEqualTo("MOTION_OBSERVATION_UNCERTAIN");
        assertThat(step(id,"idle-west").path("repairCount").asInt()).isZero();
        verify(provider,never()).editAnimation(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=? AND label='idle-west'",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_lessons WHERE source_job_id=? AND source_label='idle-west'",Integer.class,id)).isZero();
        publicStatus(404);
    }
    @Test void cosmeticWarningsPersistThroughSystemApprovalWithoutPaidRepairOrLearning()throws Exception {
        UUID id=recoveryRequest(0,false);
        doAnswer(c->{
            var r=(tools.jackson.databind.node.ObjectNode)automaticSeedReport(c.getArgument(1),true);
            r.put("recoveryVersion","photo-grounded-recovery-v1").put("appearance","PASS").put("aestheticPolicy","aesthetic-warnings-v1");
            r.putArray("qualityWarnings").addObject().put("code","STYLE_VARIATION").put("blocking",false).put("severity","WARNING").put("evidence","Synthetic cosmetic warning, not a real visual finding");return r;}).when(seedQuality).reviewRecovery(any(),anyList(),any(),any());
        doAnswer(c->{
            var r=(tools.jackson.databind.node.ObjectNode)automaticMotionReport(true);
            r.put("motionReviewVersion","motion-observation-tristate-v4").put("motionDecision","PASS").put("referencePoseUsable",true).put("aestheticPolicy","aesthetic-warnings-v1");
            r.put("referenceFrameSha256",sha(c.<List<byte[]>>getArgument(1).get(List.of("south","north","west","east").indexOf(c.getArgument(4)))));
            r.set("reviewedFrameHashes",json.valueToTree(c.<List<byte[]>>getArgument(2).stream().map(this::sha).toList()));
            r.putArray("qualityWarnings").addObject().put("code","PALETTE_UNCERTAIN").put("blocking",false).put("severity","WARNING").put("evidence","Synthetic palette warning");return r;}).when(quality).review(any(),anyList(),anyList(),anyString(),anyString());
        finish(id);var job=read(id);assertThat(job.path("status").asText()).isEqualTo("APPROVED");
        assertThat(job.at("/qualityApproval/actor").asText()).isEqualTo("SYSTEM");publicStatus(200);
        for(var step:job.path("steps")){assertThat(step.at("/qualityReport/qualityWarnings").size()).isEqualTo(1);assertThat(step.path("repairCount").asInt()).isZero();}
        verify(provider,never()).editSeeds(any());verify(provider,never()).editSeedEyes(any());verify(provider,never()).editAnimation(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=?",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_lessons WHERE source_job_id=?",Integer.class,id)).isZero();
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"PRESERVED","UNCERTAIN"})
    void deployedCoatWarningReplaysThroughProductionReviewerAndAutomaticApproval(String identity)throws Exception {
        UUID id=recoveryRequest(0,false);
        var seeds=org.shelterconnect.api.asset.CoatIdentityReplay.seeds();
        var observation=org.shelterconnect.api.asset.CoatIdentityReplay.syntheticObservation(json,identity);
        doAnswer(c->{outsideTransaction();var dirs=new LinkedHashMap<String,String>();
            for(int i=0;i<4;i++)dirs.put(List.of("south","north","west","east").get(i),Base64.getEncoder().encodeToString(seeds.get(i)));
            return json.valueToTree(Map.of("status","COMPLETED","directions",dirs));}).when(provider).poll(any(),eq(true));
        doAnswer(c->{outsideTransaction();return org.shelterconnect.api.asset.CoatIdentityReplay.review(json,c.getArgument(0),c.getArgument(1),c.getArgument(3),observation);
        }).when(seedQuality).reviewRecovery(any(),anyList(),any(),any());
        finish(id);var job=read(id);var base=step(id,"character");
        assertThat(base.at("/qualityReport/qualityWarnings/0/code").asText()).isEqualTo("COAT_APPEARANCE_UNCERTAIN");
        assertThat(base.at("/qualityReport/coatEvidence/originalPropertyReview/views/0/identityMatches").asBoolean()).isFalse();
        assertThat(base.path("repairCount").asInt()).isZero();
        if(identity.equals("PRESERVED")) {
            assertThat(job.path("status").asText()).as("failure %s",job.path("failureCode")).isEqualTo("APPROVED");
            assertThat(job.at("/qualityApproval/actor").asText()).isEqualTo("SYSTEM");
            assertThat(job.at("/seedReview/actor").asText()).isEqualTo("SYSTEM");publicStatus(200);
            assertThat(job.path("steps").size()).isEqualTo(13);
        } else {
            assertThat(job.path("status").asText()).isEqualTo("SEED_REVIEW");
            assertThat(job.path("qualityApproval").isNull()).isTrue();publicStatus(404);
            verify(provider,never()).submit(eq(false),any());
        }
        verify(provider,never()).editSeeds(any());verify(provider,never()).editSeedEyes(any());verify(provider,never()).editAnimation(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=? AND label='character'",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_lessons WHERE source_job_id=? AND source_label='character'",Integer.class,id)).isZero();
    }
    @Test void recoveryEditsBaseThenOnlyFailedMotionAndPublishesBoundFortyPixelBundle()throws Exception {
        UUID id=recoveryRequest(3,true);finish(id);var j=read(id);
        assertThat(j.path("status").asText()).as("failure %s",j.path("failureCode")).isEqualTo("APPROVED");
        assertThat(j.at("/qualityPolicy/recoveryVersion").asText()).isEqualTo("photo-grounded-recovery-v1");
        assertThat(step(id,"character").path("repairCount").asInt()).isEqualTo(3);
        assertThat(step(id,"walk-south").path("repairCount").asInt()).isEqualTo(1);
        verify(provider,times(1)).submit(eq(true),any());verify(provider,times(3)).editSeeds(any());verify(provider,times(1)).editAnimation(any());
        var manifest=get(null,"/v1/dogs/"+dog+"/assets",200).path("data");
        assertThat(manifest.at("/frameSize/width").asInt()).isEqualTo(40);
        assertThat(manifest.at("/baseFrameSize/width").asInt()).isEqualTo(32);
        assertThat(manifest.at("/mapDirections/DOWN/WALK/frames/8/x").asInt()).isEqualTo(320);
        assertThat(objects.keySet()).anyMatch(k->k.contains("raw-edits/walk-south-1"));
        for(var step:j.path("steps"))if(!step.path("action").asText().equals("BASE")){
            assertThat(step.at("/result/frameHashes").size()).isEqualTo(9);
            assertThat(step.at("/result/frameHashes")).isEqualTo(step.at("/qualityReport/frameHashes"));
            assertThat(step.at("/qualityReport/firstFrameUnchanged").asBoolean()).isTrue();}
        clearInvocations(provider);for(int i=0;i<3;i++)tick();
        assertThat(post(subject,"/v1/shelter-admin/dogs/"+dog+"/styled-assets",automaticInput(),202).at("/data/id").asText()).isEqualTo(id.toString());verifyNoInteractions(provider);
        jdbc.update("UPDATE shelter.asset_jobs SET status='RUNNING',quality_approval=NULL,reviewed_at=NULL,lease_token=NULL,lease_until=NULL WHERE id=?",id);
        jdbc.update("UPDATE shelter.styled_asset_steps SET quality_report=jsonb_set(quality_report,'{frameHashes}','[]'::jsonb) WHERE job_id=? AND label='walk-south'",id);
        tick();assertThat(read(id).path("status").asText()).isEqualTo("REVIEW");publicStatus(404);verifyNoInteractions(provider);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"BOTH","WEST_ONLY","NONE"})
    void tailLocalizationHoldAllowsConfirmedRepairButNeverApprovesUncertainTail(String scope)throws Exception {
        boolean confirmed=!scope.equals("NONE");
        UUID id=recoveryRequest(0,false);var reviews=new java.util.concurrent.atomic.AtomicInteger();
        when(seedQuality.reviewRecovery(any(),anyList(),any(),any())).thenAnswer(c->{outsideTransaction();boolean repaired=reviews.getAndIncrement()>0;
            var r=(tools.jackson.databind.node.ObjectNode)automaticSeedReport(c.getArgument(1),repaired);
            r.put("recoveryVersion","photo-grounded-recovery-v1").put("appearance",repaired?"PASS":"FAIL")
                .put("repairDescription","Match the complete visible side tail silhouettes while preserving all other anatomy.");
            var views=json.createArrayNode();for(String d:List.of("south","north","west","east")){
                boolean side=List.of("west","east").contains(d);
                boolean known=side && (scope.equals("BOTH") || scope.equals("WEST_ONLY") && d.equals("west"));
                var v=json.createObjectNode().put("direction",d).put("confidence",!repaired && side && !known?.74:.94);
                for(String f:List.of("identityMatches","eyesReadable","styleMatches","directionCorrect","tailPlausible"))v.put(f,true);
                v.set("issues",json.valueToTree(!repaired && known?List.of("TAIL_CARRIAGE"):List.of()));
                if(side)v.put("tailObservationUncertain",true).put("repairEvidenceSource",known?"GENERAL_PROPERTY_REVIEW":"UNRESOLVED_OBSERVATION");views.add(v);
            }
            r.set("propertyReview",json.valueToTree(Map.of("views",views,"tailConsistent",repaired || !confirmed)));
            var tail=(tools.jackson.databind.node.ObjectNode)r.path("tailEvidence");tail.put("passed",false);
            tail.set("failedDirections",json.valueToTree(List.of("east")));tail.set("uncertainDirections",json.valueToTree(List.of("east")));
            return r;
        });
        finish(id);var j=read(id);assertThat(j.path("status").asText()).isEqualTo("SEED_REVIEW");
        assertThat(j.path("failureCode").asText()).isEqualTo("SEED_OBSERVATION_UNCERTAIN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=?",Integer.class,id)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_lessons WHERE source_job_id=?",Integer.class,id)).isZero();
        assertThat(step(id,"character").path("qualityReport").path("tailEvidence").has("uncertainDirections")).isTrue();
        assertThat(j.path("seedReview").isNull()).isTrue();assertThat(j.path("qualityApproval").isNull()).isTrue();
        verify(provider,times(confirmed?1:0)).editSeeds(any());verify(provider,never()).submit(eq(false),any());publicStatus(404);
        assertThat(step(id,"character").path("repairCount").asInt()).isEqualTo(confirmed?1:0);
        if(confirmed)verify(provider).pollSeeds(any(),eq(scope.equals("BOTH")?List.of("west","east"):List.of("west")));
        if(scope.equals("WEST_ONLY")){
            var current=step(id,"character").path("result");
            var previous=json.readTree(jdbc.queryForObject("SELECT attempt_history->0 FROM shelter.styled_asset_steps WHERE job_id=? AND label='character'",String.class,id));
            assertThat(current.at("/hashes/east")).isEqualTo(previous.at("/result/hashes/east"));
            assertThat(current.at("/selectedRepair/rawKeys").propertyNames()).containsExactly("west");
            assertThat(current.at("/selectedRepair/plan/deferredDirections").toString()).isEqualTo("[\"east\"]");
        }
    }
    JsonNode b75MotionReport(List<byte[]> sources,List<byte[]> frames,String direction,boolean passed,String decision) {
        var r=(tools.jackson.databind.node.ObjectNode)automaticMotionReport(passed);
        r.put("motionReviewVersion","motion-observation-tristate-v4").put("motionDecision",decision).put("referencePoseUsable",true)
            .put("note","Synthetic state-transition verdict, not evidence of real image quality.");
        r.put("referenceFrameSha256",sha(sources.get(List.of("south","north","west","east").indexOf(direction))));
        r.set("reviewedFrameHashes",json.valueToTree(frames.stream().map(this::sha).toList()));
        if(!passed)r.set("issues",json.valueToTree(List.of("IDLE_MOTION")));return r;
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void exhaustedIdleUsesOneExplicitStaticAlternativeAndFreshReviewWithoutExtraPaidCalls(boolean fallbackPass)throws Exception {
        UUID id=recoveryRequest(0,false);var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("north"))).thenAnswer(c->{outsideTransaction();
            calls.incrementAndGet();
            boolean derived=jdbc.queryForObject("SELECT COALESCE(provider_result->'derivation'->>'strategy','') FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-north'",String.class,id).equals("approved-seed-idle-hold-v1");
            boolean passed=derived && fallbackPass;
            return b75MotionReport(c.getArgument(1),c.getArgument(2),"north",passed,passed?"PASS":"CONFIRMED_DEFECT");});
        finish(id);var s=step(id,"idle-north");
        assertThat(read(id).path("status").asText()).isEqualTo(fallbackPass?"APPROVED":"REVIEW");
        assertThat(calls.get()).isGreaterThanOrEqualTo(3);assertThat(s.path("repairCount").asInt()).isEqualTo(3);
        assertThat(s.at("/result/derivation/strategy").asText()).isEqualTo("approved-seed-idle-hold-v1");
        assertThat(s.at("/result/frameHashes").size()).isEqualTo(9);
        assertThat(s.at("/result/frameHashes").valueStream().map(JsonNode::asText).distinct().count()).isEqualTo(1);
        assertThat(s.at("/result/key").asText()).contains("/sheets/idle-hold/");
        assertThat(s.at("/result/rawEdit").isMissingNode()).isTrue();
        var history=json.readTree(jdbc.queryForObject("SELECT attempt_history FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-north'",String.class,id));
        assertThat(history.size()).isEqualTo(4);var last=history.get(3);
        assertThat(last.path("idleHoldFallback").asBoolean()).isTrue();assertThat(last.path("repairCount").asInt()).isEqualTo(3);
        assertThat(last.at("/quality/passed").asBoolean()).isFalse();assertThat(last.path("providerJobId").isNull()).isFalse();
        assertThat(objects).containsKey(last.at("/result/key").asText()).containsKey(last.at("/result/rawEdit/key").asText());
        assertThat(sha(objects.get(last.at("/result/key").asText()))).isEqualTo(last.at("/result/sha256").asText());
        verify(provider,times(3)).editAnimation(any());verify(provider,times(12)).submit(eq(false),any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_submissions WHERE job_id=?",Integer.class,id)).isEqualTo(16);
        clearInvocations(provider);for(int i=0;i<3;i++)tick();verifyNoInteractions(provider);
        if(fallbackPass){var manifest=get(null,"/v1/dogs/"+dog+"/assets",200).path("data");assertThat(manifest.at("/mapDirections/UP/IDLE/frames").size()).isEqualTo(9);}
        else publicStatus(404);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void paletteAmbiguityUsesOneFreshStaticIdleWithoutSpendingRemainingPaidBudget(boolean fallbackPass)throws Exception {
        UUID id=recoveryRequest(0,false);
        var actual=json.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("scripts/fixtures/native-rgba-v29/idle-west-v29-review.json")));
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("west"))).thenAnswer(c->{outsideTransaction();
            boolean derived=jdbc.queryForObject("SELECT COALESCE(provider_result->'derivation'->>'strategy','') FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-west'",String.class,id).equals("approved-seed-idle-hold-v1");
            if(derived)return b75MotionReport(c.getArgument(1),c.getArgument(2),"west",fallbackPass,fallbackPass?"PASS":"CONFIRMED_DEFECT");
            var r=(tools.jackson.databind.node.ObjectNode)actual.deepCopy();r.put("rulesSha256",currentRules());
            r.put("note","Synthetic state transition using archived ambiguity, not real image verification.");return r;
        });
        finish(id);var s=step(id,"idle-west");
        assertThat(read(id).path("status").asText()).isEqualTo(fallbackPass?"APPROVED":"REVIEW");
        assertThat(s.path("repairCount").asInt()).isZero();
        assertThat(s.at("/result/derivation/strategy").asText()).isEqualTo("approved-seed-idle-hold-v1");
        assertThat(s.at("/result/frameHashes").valueStream().map(JsonNode::asText).distinct().count()).isEqualTo(1);
        var history=json.readTree(jdbc.queryForObject("SELECT attempt_history->-1 FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-west'",String.class,id));
        assertThat(history.path("idleHoldFallback").asBoolean()).isTrue();assertThat(history.at("/quality/passed").asBoolean()).isFalse();
        assertThat(history.at("/quality/rawEditReview/motionDecision").asText()).isEqualTo("UNCERTAIN");
        verify(provider,never()).editAnimation(any());verify(provider,times(12)).submit(eq(false),any());
        clearInvocations(provider);for(int i=0;i<3;i++)tick();verifyNoInteractions(provider);
        if(!fallbackPass)publicStatus(404);
    }

    UUID b75HeldPack()throws Exception {
        UUID id=recoveryRequest(0,false);
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("south"))).thenAnswer(c->b75MotionReport(c.getArgument(1),c.getArgument(2),"south",false,"UNCERTAIN"));
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("REVIEW");return id;
    }
    @Test void confirmedTailRepairPreservesUncertainRecordAndRequiresFreshPassingResult()throws Exception {
        UUID id=recoveryRequest(0,false);var calls=new java.util.concurrent.atomic.AtomicInteger();
        var actual=json.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("scripts/fixtures/idle-hold-v26/walk-north-v26-review.json")));
        when(quality.review(any(),anyList(),anyList(),eq("WALK"),eq("north"))).thenAnswer(c->{outsideTransaction();
            if(calls.getAndIncrement()>0)return b75MotionReport(c.getArgument(1),c.getArgument(2),"north",true,"PASS");
            var r=(tools.jackson.databind.node.ObjectNode)actual.deepCopy();r.put("rulesSha256",currentRules());return r;});
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(step(id,"walk-north").path("repairCount").asInt()).isEqualTo(1);
        var history=json.readTree(jdbc.queryForObject("SELECT attempt_history FROM shelter.styled_asset_steps WHERE job_id=? AND label='walk-north'",String.class,id));
        assertThat(history.get(0).at("/quality/motionDecision").asText()).isEqualTo("UNCERTAIN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=? AND label='walk-north' AND NOT passed",Integer.class,id)).isZero();
        assertThat(calls.get()).isGreaterThanOrEqualTo(2);verify(provider,times(1)).editAnimation(any());
    }
    @Test void confirmedPaletteRepairKeepsUncertaintyUnlearnedAndRequiresFreshQuality()throws Exception {
        UUID id=recoveryRequest(0,false);var calls=new java.util.concurrent.atomic.AtomicInteger();
        var actual=json.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("scripts/fixtures/sit-conflicts-v28/sit-north-review.json")));
        when(quality.review(any(),anyList(),anyList(),eq("SIT"),eq("north"))).thenAnswer(c->{outsideTransaction();
            if(calls.getAndIncrement()>0)return b75MotionReport(c.getArgument(1),c.getArgument(2),"north",true,"PASS");
            var r=(tools.jackson.databind.node.ObjectNode)actual.deepCopy();r.put("rulesSha256",currentRules());return r;});
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(step(id,"sit-north").path("repairCount").asInt()).isEqualTo(1);
        var history=json.readTree(jdbc.queryForObject("SELECT attempt_history FROM shelter.styled_asset_steps WHERE job_id=? AND label='sit-north'",String.class,id));
        assertThat(history.get(0).at("/quality/motionDecision").asText()).isEqualTo("UNCERTAIN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=? AND label='sit-north' AND NOT passed",Integer.class,id)).isZero();
        verify(provider,times(1)).editAnimation(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void confirmedTailWithTentativeIdentityRepairsAutomaticallyButNeedsFreshPassingCandidate(boolean candidatePass)throws Exception {
        UUID id=recoveryRequest(0,false);var calls=new java.util.concurrent.atomic.AtomicInteger();
        var actual=json.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("scripts/fixtures/confirmed-tail-v33/review.json")));
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("south"))).thenAnswer(c->{outsideTransaction();
            if(calls.getAndIncrement()>0)return b75MotionReport(c.getArgument(1),c.getArgument(2),"south",candidatePass,candidatePass?"PASS":"UNCERTAIN");
            // Real archived verdict, synthetic provider images: this checks worker behavior, not new visual quality.
            var r=(tools.jackson.databind.node.ObjectNode)actual.deepCopy();r.put("rulesSha256",currentRules());return r;});
        finish(id);var job=read(id);var motion=step(id,"idle-south");
        assertThat(job.path("status").asText()).isEqualTo(candidatePass?"APPROVED":"REVIEW");
        assertThat(motion.path("repairCount").asInt()).isEqualTo(1);
        assertThat(job.at("/qualityPolicy/maxRepairsPerClip").asInt()).isEqualTo(3);
        var history=json.readTree(jdbc.queryForObject("SELECT attempt_history FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-south'",String.class,id));
        var old=history.get(0).path("quality");
        assertThat(old.path("motionDecision").asText()).isEqualTo("UNCERTAIN");
        assertThat(old.path("initialVision")).isEqualTo(actual.path("initialVision"));
        assertThat(old.path("consistencyReview")).isEqualTo(actual.path("consistencyReview"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=? AND label='idle-south' AND NOT passed",Integer.class,id)).isZero();
        assertThat(calls.get()).isGreaterThanOrEqualTo(2);verify(provider,times(1)).editAnimation(any());
        if(!candidatePass){assertThat(job.path("qualityApproval").isNull()).isTrue();publicStatus(404);}
    }
    @Test void nativeProviderEntryShadingSurvivesTheActualEditPersistencePath()throws Exception {
        UUID id=recoveryRequest(0,true);
        for(int i=0;i<10 && !step(id,"character").path("status").asText().equals("SUCCEEDED");i++)tick();
        var base=ImageIO.read(new ByteArrayInputStream(objects.get(step(id,"character").at("/result/keys/south").asText())));
        var frame=new BufferedImage(40,40,BufferedImage.TYPE_INT_ARGB);
        for(int y=0;y<32;y++)for(int x=0;x<32;x++){int pixel=base.getRGB(x,y);frame.setRGB(x+4,y+4,(pixel>>>24)==0?pixel:(pixel&0xff000000)|0x997744);}
        var bytes=new ByteArrayOutputStream();ImageIO.write(frame,"png",bytes);byte[] providerFrame=bytes.toByteArray();
        doAnswer(c->json.valueToTree(Map.of("status","COMPLETED","frames",Collections.nCopies(9,Base64.getEncoder().encodeToString(providerFrame))))).when(provider).poll(any(),eq(false));
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
        var motion=step(id,"walk-south");assertThat(motion.path("repairCount").asInt()).isEqualTo(1);
        var stored=ImageIO.read(new ByteArrayInputStream(objects.get(motion.at("/result/key").asText())));
        for(int i=0;i<9;i++)for(int y=0;y<40;y++)for(int x=0;x<40;x++)assertThat(stored.getRGB(i*40+x,y)).isEqualTo(frame.getRGB(x,y));
        assertThat(motion.at("/result/sha256")).isEqualTo(motion.at("/result/rawEdit/sha256"));
        assertThat(motion.at("/qualityReport/firstFrameUnchanged").asBoolean()).isFalse();
        verify(provider,times(1)).editAnimation(any());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"adopt","audit-only","uncertain","revoked"})
    void passedRawCandidateIsAdoptedAtomicallyWithNoPaidCallsAndRejectedEvidenceRetained(String mode)throws Exception {
        UUID id=recoveryRequest(0,false);finish(id);
        var prior=(tools.jackson.databind.node.ObjectNode)step(id,"idle-west").path("result").deepCopy();
        var source=ImageIO.read(new ByteArrayInputStream(objects.get(prior.path("key").asText())));
        // Recreate legacy first-frame replacement in a synthetic DB fixture, not in any real job.
        for(int i=0;i<9;i++)source.setRGB(i*40+16,13,0xff997744);
        var rawBytes=new ByteArrayOutputStream();ImageIO.write(source,"png",rawBytes);byte[] raw=rawBytes.toByteArray();
        source.setRGB(16,13,0xff224477);
        var oldBytes=new ByteArrayOutputStream();ImageIO.write(source,"png",oldBytes);byte[] old=oldBytes.toByteArray();
        String prefix=dog+"/"+id+"/native-32/",rawKey=prefix+"raw-edits/idle-west-legacy.png",oldKey=prefix+"sheets/legacy-fixture/idle-west.png";
        objects.put(rawKey,raw);objects.put(oldKey,old);prior.put("key",oldKey).put("sha256",sha(old));
        prior.putObject("rawEdit").put("key",rawKey).put("sha256",sha(raw));
        var held=(tools.jackson.databind.node.ObjectNode)step(id,"idle-west").path("qualityReport").deepCopy();
        held.put("passed",false).put("inputSha256",sha(old));held.putArray("issues").add("IDENTITY_DRIFT");
        jdbc.update("UPDATE shelter.asset_jobs SET status='REVIEW',quality_approval=NULL,reviewed_at=NULL WHERE id=?",id);
        jdbc.update("UPDATE shelter.styled_asset_steps SET result=?::jsonb,quality_report=?::jsonb WHERE job_id=? AND label='idle-west'",prior.toString(),held.toString(),id);
        var template=json.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("scripts/fixtures/native-rgba-v29/idle-west-review.json"))).path("rawEditReview");
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("west"))).thenAnswer(c->{outsideTransaction();
            var frames=c.<List<byte[]>>getArgument(2);boolean isRaw=ImageIO.read(new ByteArrayInputStream(frames.getFirst())).getRGB(16,13)==0xff997744;
            var report=(tools.jackson.databind.node.ObjectNode)template.deepCopy();
            report.put("rulesSha256",currentRules()).put("referenceFrameSha256",sha(c.<List<byte[]>>getArgument(1).get(2)));
            report.set("reviewedFrameHashes",json.valueToTree(frames.stream().map(this::sha).toList()));
            report.put("passed",isRaw && !mode.equals("uncertain")).put("motionDecision",isRaw?(mode.equals("uncertain")?"UNCERTAIN":"PASS"):"CONFIRMED_DEFECT");
            report.put("note","Synthetic transaction test verdict; original image quality is tested separately.");
            if(!isRaw)report.putArray("issues").add("IDENTITY_DRIFT");
            if(mode.equals("uncertain"))report.putArray("uncertainProperties").add("tail");
            if(isRaw && mode.equals("revoked"))jdbc.update("UPDATE shelter.asset_source_permissions SET revoked_at=now() WHERE id=?",permission);
            var reversed=json.createObjectNode();var keys=new ArrayList<>(report.propertyNames());Collections.reverse(keys);keys.forEach(k->reversed.set(k,report.path(k)));return reversed;
        });
        int submissions=jdbc.queryForObject("SELECT count(*) FROM shelter.asset_submissions WHERE job_id=?",Integer.class,id);
        if(mode.equals("audit-only"))post(subject,path(id)+"/quality-recheck",seedRecheckBody(id),200);
        else {var body=b75ResumeBody(id);body.put("expectedSheetHashes",Map.of("idle-west",sha(old)));post(subject,path(id)+"/motion-repair-resume",body,200);}
        clearInvocations(provider);finish(id);verifyNoInteractions(provider);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.asset_submissions WHERE job_id=?",Integer.class,id)).isEqualTo(submissions);
        var after=step(id,"idle-west");assertThat(after.path("repairCount").asInt()).isZero();
        if(mode.equals("adopt")) {
            assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
            assertThat(after.at("/result/sha256").asText()).isEqualTo(sha(raw));
            assertThat(objects.get(after.at("/result/key").asText())).isEqualTo(raw);
            assertThat(after.at("/result/derivation/strategy").asText()).isEqualTo("native-provider-rgba-v1");
            var history=json.readTree(jdbc.queryForObject("SELECT attempt_history->-1 FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-west'",String.class,id));
            assertThat(history.path("rawMotionAdoption").asBoolean()).isTrue();
            assertThat(history.at("/quality/passed").asBoolean()).isFalse();
            assertThat(history.at("/quality/rawEditReview/passed").asBoolean()).isTrue();
            assertThat(history.at("/result/sha256").asText()).isEqualTo(sha(old));
            assertThat(objects.get(oldKey)).isEqualTo(old);assertThat(objects.get(rawKey)).isEqualTo(raw);
            // Reproduce a legacy insertion-order digest after JSONB has reordered the same report.
            var legacy=(tools.jackson.databind.node.ObjectNode)after.path("result").deepCopy();var proof=(tools.jackson.databind.node.ObjectNode)legacy.path("derivation");
            var reordered=json.createObjectNode();var keys=new ArrayList<>(proof.path("rawProviderReview").propertyNames());Collections.reverse(keys);
            keys.forEach(k->reordered.set(k,proof.path("rawProviderReview").path(k)));String legacyDigest=sha(json.writeValueAsBytes(reordered));
            proof.remove("rawReviewHashVersion");proof.put("rawReviewSha256",legacyDigest);
            String savedHistory=jdbc.queryForObject("SELECT attempt_history::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-west'",String.class,id);
            jdbc.update("UPDATE shelter.styled_asset_steps SET result=?::jsonb,attempt_history='[]'::jsonb WHERE job_id=? AND label='idle-west'",legacy.toString(),id);
            jdbc.update("UPDATE shelter.asset_jobs SET status='REVIEW',quality_approval=NULL,reviewed_at=NULL,failure_code='AUTO_APPROVAL_EVIDENCE_REQUIRED' WHERE id=?",id);
            var hashes=new HashMap<String,String>();read(id).path("steps").forEach(st->{if(!st.path("action").asText().equals("BASE"))hashes.put(st.path("label").asText(),st.at("/result/sha256").asText());});
            var evidence=Map.of("note","Recheck only final evidence without changing images or quality observations","evidenceOnly",true,"expectedRulesSha256",currentRules(),"expectedSeedHashes",read(id).at("/steps/0/result/hashes"),"expectedSheetHashes",hashes);
            clearInvocations(provider,quality,seedQuality);
            post(subject,path(id)+"/quality-recheck",evidence,200);assertThat(read(id).path("status").asText()).isEqualTo("REVIEW");publicStatus(404);
            jdbc.update("UPDATE shelter.styled_asset_steps SET attempt_history=?::jsonb WHERE job_id=? AND label='idle-west'",savedHistory,id);
            post(subject,path(id)+"/quality-recheck",evidence,200);assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
            var fixed=step(id,"idle-west");assertThat(fixed.path("qualityReport")).isEqualTo(after.path("qualityReport"));
            assertThat(fixed.at("/result/sha256")).isEqualTo(after.at("/result/sha256"));assertThat(fixed.at("/result/frameHashes")).isEqualTo(after.at("/result/frameHashes"));
            assertThat(fixed.at("/result/derivation/legacyRawReviewSha256").asText()).isEqualTo(legacyDigest);
            post(subject,path(id)+"/quality-recheck",evidence,200);verifyNoInteractions(provider,quality,seedQuality);after=fixed;
            var original=after.deepCopy();for(int i=0;i<3;i++)tick();assertThat(step(id,"idle-west")).isEqualTo(original);verifyNoInteractions(provider);
            // Simulate another held clip in a private pack; approved/public packs are immutable.
            jdbc.update("UPDATE shelter.asset_jobs SET status='REVIEW',quality_approval=NULL,reviewed_at=NULL WHERE id=?",id);
            jdbc.update("UPDATE shelter.styled_asset_steps SET quality_report=jsonb_set(quality_report,'{passed}','false') WHERE job_id=? AND label='sit-south'",id);
            post(subject,path(id)+"/quality-recheck",seedRecheckBody(id),200);finish(id);
            assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
            assertThat(step(id,"idle-west").path("result")).isEqualTo(after.path("result"));verifyNoInteractions(provider);
            // Final publication also checks the retained raw QA and provenance.
            jdbc.update("UPDATE shelter.asset_jobs SET status='RUNNING',quality_approval=NULL,reviewed_at=NULL WHERE id=?",id);
            jdbc.update("UPDATE shelter.styled_asset_steps SET result=jsonb_set(result,'{derivation,sourceRawSha256}',to_jsonb(CAST(? AS text))) WHERE job_id=? AND label='idle-west'","0".repeat(64),id);
            tick();assertThat(read(id).path("status").asText()).isEqualTo("REVIEW");publicStatus(404);
        } else {
            assertThat(read(id).path("status").asText()).isEqualTo(mode.equals("revoked")?"CANCELLED":"REVIEW");
            assertThat(after.at("/result/sha256").asText()).isEqualTo(sha(old));publicStatus(404);
        }
    }

    JsonNode candidateConflict()throws Exception {
        var r=(tools.jackson.databind.node.ObjectNode)json.readTree(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("scripts/fixtures/native-rgba-v29/sit-north-v30-review.json")));
        // Synthetic current-policy observations for the mocked worker; the stored real receipt is unchanged.
        r.put("rulesSha256",currentRules());
        for(String key:List.of("rawEditReview","restoredReview"))if(r.has(key))((tools.jackson.databind.node.ObjectNode)r.path(key)).put("rulesSha256",currentRules());
        return r;
    }
    UUID candidateHeldPack()throws Exception {
        UUID id=recoveryRequest(0,false);finish(id);
        // Synthetic held fixture; runtime jobs are never changed this way.
        var r=(tools.jackson.databind.node.ObjectNode)step(id,"sit-north").path("qualityReport").deepCopy();var observed=candidateConflict();
        for(String f:List.of("initialVision","consistencyReview","observationCount","uncertainProperties","confirmedProperties","motionDecision","motionReviewVersion"))r.set(f,observed.path(f));
        r.put("passed",false);r.putArray("issues");
        jdbc.update("UPDATE shelter.asset_jobs SET status='REVIEW',quality_approval=NULL,reviewed_at=NULL WHERE id=?",id);
        jdbc.update("UPDATE shelter.styled_asset_steps SET quality_report=?::jsonb WHERE job_id=? AND label='sit-north'",r.toString(),id);
        return id;
    }
    Map<String,Object> candidateBody(UUID id)throws Exception {
        var s=step(id,"sit-north");
        return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"note","Use the exact current evidence for a bounded candidate and preserve every passing clip",
            "expectedSeedHashes",step(id,"character").at("/result/hashes"),"expectedRulesSha256",currentRules(),
            "expectedSheetHashes",Map.of("sit-north",s.at("/result/sha256").asText()),
            "expectedReviewHashes",Map.of("sit-north",sha(json.writeValueAsBytes(s.path("qualityReport"))))));
    }
    @Test void savedEvidenceContinuationNeverRejudgesPassingClipsOrResetsBudget()throws Exception {
        UUID id=candidateHeldPack();var before=read(id);var body=candidateBody(id);var originalSeed=before.path("seedReview");
        clearInvocations(provider,quality,seedQuality);
        post(subject,path(id)+"/motion-candidate-repair",body,200);post(subject,path(id)+"/motion-candidate-repair",body,200);
        tick();verifyNoInteractions(provider,quality,seedQuality);assertThat(step(id,"sit-north").path("repairCount").asInt()).isEqualTo(1);
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(read(id).path("seedReview")).isEqualTo(originalSeed);
        for(var old:before.path("steps"))if(!old.path("label").asText().equals("sit-north"))assertThat(step(id,old.path("label").asText())).isEqualTo(old);
        verify(provider,times(1)).editAnimation(any());verify(quality,times(1)).review(any(),anyList(),anyList(),eq("SIT"),eq("north"));verifyNoInteractions(seedQuality);
        var h=json.readTree(jdbc.queryForObject("SELECT attempt_history->-1 FROM shelter.styled_asset_steps WHERE job_id=? AND label='sit-north'",String.class,id));
        assertThat(h.path("unconfirmedMotionCandidate").asBoolean()).isTrue();assertThat(h.at("/quality/motionDecision").asText()).isEqualTo("UNCERTAIN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=? AND label='sit-north' AND NOT passed",Integer.class,id)).isZero();
        clearInvocations(provider,quality,seedQuality);post(subject,path(id)+"/motion-candidate-repair",body,200);tick();verifyNoInteractions(provider,quality,seedQuality);
        body.put("note","Changed note cannot reuse a completed candidate request identifier");post(subject,path(id)+"/motion-candidate-repair",body,409);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void newGenerationTriesOnlyOneUnconfirmedCandidateAndRequiresFreshPass(boolean pass)throws Exception {
        UUID id=recoveryRequest(0,false);var calls=new java.util.concurrent.atomic.AtomicInteger();var conflict=candidateConflict();
        when(quality.review(any(),anyList(),anyList(),eq("SIT"),eq("north"))).thenAnswer(c->{outsideTransaction();
            if(calls.getAndIncrement()>0 && pass)return b75MotionReport(c.getArgument(1),c.getArgument(2),"north",true,"PASS");
            return conflict.deepCopy();});
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo(pass?"APPROVED":"REVIEW");
        assertThat(step(id,"sit-north").path("repairCount").asInt()).isEqualTo(1);verify(provider,times(1)).editAnimation(any());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=? AND label='sit-north' AND NOT passed",Integer.class,id)).isZero();
        if(!pass){publicStatus(404);post(subject,path(id)+"/motion-candidate-repair",candidateBody(id),409);}
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"seed","sheet","review","rules","passing","incomplete","unauthorized","revoked","budget","unknown","duplicate","revoked-after"})
    void currentEvidenceCandidateRejectsChangedInputsMissingRightsAndRepeatedSpending(String defect)throws Exception {
        UUID id=candidateHeldPack();var body=candidateBody(id);UUID actor=subject;int expected=409;
        switch(defect) {
            case "seed" -> body.put("expectedSeedHashes",Map.of("south","0".repeat(64),"north","0".repeat(64),"west","0".repeat(64),"east","0".repeat(64)));
            case "sheet" -> body.put("expectedSheetHashes",Map.of("sit-north","0".repeat(64)));
            case "review" -> body.put("expectedReviewHashes",Map.of("sit-north","0".repeat(64)));
            case "rules" -> body.put("expectedRulesSha256","0".repeat(64));
            case "passing" -> {body.put("expectedSheetHashes",Map.of("walk-south",step(id,"walk-south").at("/result/sha256").asText()));body.put("expectedReviewHashes",Map.of("walk-south",sha(json.writeValueAsBytes(step(id,"walk-south").path("qualityReport")))));}
            case "incomplete" -> jdbc.update("UPDATE shelter.styled_asset_steps SET status='PENDING' WHERE job_id=? AND label='walk-south'",id);
            case "unauthorized" -> {actor=UUID.randomUUID();expected=403;}
            case "revoked" -> jdbc.update("UPDATE shelter.asset_source_permissions SET revoked_at=now() WHERE id=?",permission);
            case "budget" -> jdbc.update("UPDATE shelter.styled_asset_steps SET repair_count=3 WHERE job_id=? AND label='sit-north'",id);
            case "unknown" -> {var q=(tools.jackson.databind.node.ObjectNode)step(id,"sit-north").path("qualityReport").deepCopy();q.remove("initialVision");jdbc.update("UPDATE shelter.styled_asset_steps SET quality_report=?::jsonb WHERE job_id=? AND label='sit-north'",q.toString(),id);body=candidateBody(id);}
            case "duplicate" -> {post(subject,path(id)+"/motion-candidate-repair",body,200);body.put("requestId",UUID.randomUUID().toString());}
            case "revoked-after" -> {post(subject,path(id)+"/motion-candidate-repair",body,200);jdbc.update("UPDATE shelter.asset_source_permissions SET revoked_at=now() WHERE id=?",permission);}
        }
        clearInvocations(provider,quality,seedQuality);
        if(defect.equals("revoked-after")){tick();assertThat(read(id).path("status").asText()).isEqualTo("CANCELLED");}
        else post(actor,path(id)+"/motion-candidate-repair",body,expected);
        verifyNoInteractions(provider,quality,seedQuality);
    }

    Map<String,Object> b75ResumeBody(UUID id)throws Exception {
        jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=jsonb_set(quality_policy,'{rulesSha256}',to_jsonb(CAST(? AS text))) WHERE id=?","0".repeat(64),id);
        jdbc.update("UPDATE shelter.styled_asset_steps SET quality_report=jsonb_set(quality_report,'{rulesSha256}',to_jsonb(CAST(? AS text))) WHERE job_id=?","0".repeat(64),id);
        jdbc.update("UPDATE shelter.asset_jobs SET seed_review=jsonb_set(seed_review,'{reportSha256}',to_jsonb(CAST(? AS text))) WHERE id=?",sha(json.writeValueAsBytes(step(id,"character").path("qualityReport"))),id);
        return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"note","Recheck the existing pack under new rules and use only the original remaining budgets",
            "expectedSeedHashes",read(id).at("/steps/0/result/hashes"),"expectedSheetHashes",Map.of("idle-south",step(id,"idle-south").at("/result/sha256").asText()),"expectedRulesSha256","0".repeat(64)));
    }
    @Test void motionResumeKeepsUnselectedBytesAndBudgetAndReplaysAfterCompletion()throws Exception {
        UUID id=b75HeldPack();var body=b75ResumeBody(id);var original=read(id);var objectsBefore=new HashMap<>(objects);var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("south"))).thenAnswer(c->{boolean pass=calls.getAndIncrement()>0;return b75MotionReport(c.getArgument(1),c.getArgument(2),"south",pass,pass?"PASS":"CONFIRMED_DEFECT");});
        clearInvocations(provider);post(subject,path(id)+"/motion-repair-resume",body,200);post(subject,path(id)+"/motion-repair-resume",body,200);
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(step(id,"idle-south").path("repairCount").asInt()).isEqualTo(1);
        assertThat(read(id).at("/qualityPolicy/maxRepairsPerClip")).isEqualTo(original.at("/qualityPolicy/maxRepairsPerClip"));
        for(var e:objectsBefore.entrySet())assertThat(objects.get(e.getKey())).isEqualTo(e.getValue());
        for(var before:original.path("steps"))if(!before.path("label").asText().equals("idle-south"))assertThat(step(id,before.path("label").asText()).path("result")).isEqualTo(before.path("result"));
        verify(provider,times(1)).editAnimation(any());verify(provider,never()).submit(anyBoolean(),any());
        clearInvocations(provider);post(subject,path(id)+"/motion-repair-resume",body,200);tick();verifyNoInteractions(provider);
        body.put("note","A changed request cannot buy additional generation for the same completed job");post(subject,path(id)+"/motion-repair-resume",body,409);
    }
    @Test void changedRuleContinuationArchivesPriorGrantAndReplaysEveryOldRequestWithoutSpending()throws Exception {
        UUID id=b75HeldPack();var first=b75ResumeBody(id);post(subject,path(id)+"/motion-repair-resume",first,200);finish(id);
        assertThat(read(id).path("status").asText()).isEqualTo("REVIEW");
        // Synthetic previous-deployment fixture, never used to alter a real job or its repair budget.
        String old="1".repeat(64);
        jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=jsonb_set(jsonb_set(quality_policy,'{rulesSha256}',to_jsonb(CAST(? AS text))),'{motionRepairResume,rulesSha256}',to_jsonb(CAST(? AS text))) WHERE id=?",old,old,id);
        jdbc.update("UPDATE shelter.styled_asset_steps SET quality_report=jsonb_set(quality_report,'{rulesSha256}',to_jsonb(CAST(? AS text))) WHERE job_id=?",old,id);
        jdbc.update("UPDATE shelter.asset_jobs SET seed_review=jsonb_set(seed_review,'{reportSha256}',to_jsonb(CAST(? AS text))) WHERE id=?",sha(json.writeValueAsBytes(step(id,"character").path("qualityReport"))),id);
        var prior=read(id).at("/qualityPolicy/motionRepairResume");var counts=read(id).path("steps").valueStream().map(s->s.path("repairCount").asInt()).toList();
        var second=new HashMap<>(first);second.put("requestId",UUID.randomUUID().toString());second.put("expectedRulesSha256",old);
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("south"))).thenAnswer(c->b75MotionReport(c.getArgument(1),c.getArgument(2),"south",true,"PASS"));
        clearInvocations(provider);post(subject,path(id)+"/motion-repair-resume",second,200);finish(id);
        assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(read(id).at("/qualityPolicy/motionRepairResumeHistory").size()).isEqualTo(1);
        assertThat(read(id).at("/qualityPolicy/motionRepairResumeHistory/0")).isEqualTo(prior);
        assertThat(read(id).path("steps").valueStream().map(s->s.path("repairCount").asInt()).toList()).isEqualTo(counts);
        post(subject,path(id)+"/motion-repair-resume",first,200);post(subject,path(id)+"/motion-repair-resume",second,200);tick();verifyNoInteractions(provider);
        first.put("note","Changing a historical request must remain rejected after a newer continuation");post(subject,path(id)+"/motion-repair-resume",first,409);
        second.put("requestId",UUID.randomUUID().toString());post(subject,path(id)+"/motion-repair-resume",second,409);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"unchanged","seed","sheet","rules","passing","incomplete","unauthorized","revoked","duplicate","exhausted-walk"})
    void motionResumeRejectsUnsafeStaleOrDuplicateRequests(String defect)throws Exception {
        UUID id=b75HeldPack();var body=b75ResumeBody(id);UUID actor=subject;int expected=409;
        switch(defect) {
            case "unchanged" -> {String current=java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(java.nio.file.Path.of("asset-styles/cozy32-v1/quality-rules.json"))));
                jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=jsonb_set(quality_policy,'{rulesSha256}',to_jsonb(CAST(? AS text))) WHERE id=?",current,id);
                jdbc.update("UPDATE shelter.styled_asset_steps SET quality_report=jsonb_set(quality_report,'{rulesSha256}',to_jsonb(CAST(? AS text))) WHERE job_id=?",current,id);body.put("expectedRulesSha256",current);
                jdbc.update("UPDATE shelter.asset_jobs SET seed_review=jsonb_set(seed_review,'{reportSha256}',to_jsonb(CAST(? AS text))) WHERE id=?",sha(json.writeValueAsBytes(step(id,"character").path("qualityReport"))),id);}
            case "seed" -> body.put("expectedSeedHashes",Map.of("south","1".repeat(64),"north","1".repeat(64),"west","1".repeat(64),"east","1".repeat(64)));
            case "sheet" -> body.put("expectedSheetHashes",Map.of("idle-south","1".repeat(64)));
            case "rules" -> body.put("expectedRulesSha256","1".repeat(64));
            case "passing" -> body.put("expectedSheetHashes",Map.of("walk-south",step(id,"walk-south").at("/result/sha256").asText()));
            case "incomplete" -> jdbc.update("UPDATE shelter.styled_asset_steps SET status='PENDING' WHERE job_id=? AND label='walk-south'",id);
            case "exhausted-walk" -> {
                jdbc.update("UPDATE shelter.styled_asset_steps SET repair_count=3,quality_report=jsonb_set(quality_report,'{passed}','false'::jsonb) WHERE job_id=? AND label='walk-south'",id);
                body.put("expectedSheetHashes",Map.of("walk-south",step(id,"walk-south").at("/result/sha256").asText()));}
            case "unauthorized" -> {actor=UUID.randomUUID();expected=403;}
            case "revoked" -> jdbc.update("UPDATE shelter.asset_source_permissions SET revoked_at=now() WHERE id=?",permission);
            case "duplicate" -> {post(subject,path(id)+"/motion-repair-resume",body,200);body.put("requestId",UUID.randomUUID().toString());}
        }
        clearInvocations(provider);post(actor,path(id)+"/motion-repair-resume",body,expected);verifyNoInteractions(provider);
    }

    UUID repairedSeedHold(int repairs)throws Exception {
        UUID id=recoveryRequest(99,false);for(int i=0;i<repairs*2;i++)tick();
        when(seedQuality.reviewRecovery(any(),anyList(),any(),any())).thenAnswer(c->{outsideTransaction();
            var r=(tools.jackson.databind.node.ObjectNode)automaticSeedReport(c.getArgument(1),false);
            return r.put("recoveryVersion","photo-grounded-recovery-v1").put("appearance","FAIL");});
        tick();tick();assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");return id;
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={1,3})
    void repairedSeedRecheckAuditsStoredBytesWithoutLoadingOrSpendingAnotherEdit(int repairs)throws Exception {
        UUID id=repairedSeedHold(repairs);
        var before=step(id,"character");var saved=new HashMap<>(objects);
        assertThat(before.path("repairCount").asInt()).isEqualTo(repairs);
        var body=seedRecheckBody(id);clearInvocations(provider,seedQuality);
        post(subject,path(id)+"/quality-recheck",body,200);tick();
        assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");
        assertThat(step(id,"character").path("result")).isEqualTo(before.path("result"));
        assertThat(step(id,"character").path("repairCount").asInt()).isEqualTo(repairs);
        verify(seedQuality).reviewRecovery(any(),anyList(),any(),any());verifyNoInteractions(provider);
        for(var e:saved.entrySet())assertThat(objects.get(e.getKey())).isEqualTo(e.getValue());
        assertThat(objects).hasSize(saved.size());
        post(subject,path(id)+"/quality-recheck",body,200);tick();verifyNoInteractions(provider);
    }
    Map<String,Object> seedResumeBody(UUID id) throws Exception {
        var j=read(id);return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"note","Resume only with the existing unspent repair budget and preserved original sprites",
            "expectedSeedHashes",j.at("/steps/0/result/hashes"),"expectedRulesSha256",j.at("/qualityPolicy/rulesSha256").asText()));
    }
    @Test void explicitSeedResumePreservesBudgetAndReplaysAfterChangedOutputWithoutBuyingAgain()throws Exception {
        UUID id=repairedSeedHold(1);var original=step(id,"character").path("result");var body=seedResumeBody(id);
        var limit=read(id).at("/qualityPolicy/maxSeedRepairs");var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(seedQuality.reviewRecovery(any(),anyList(),any(),any())).thenAnswer(c->{outsideTransaction();boolean pass=calls.getAndIncrement()>0;
            var r=(tools.jackson.databind.node.ObjectNode)automaticSeedReport(c.getArgument(1),pass);
            r.put("recoveryVersion","photo-grounded-recovery-v1").put("appearance",pass?"PASS":"FAIL").put("repairDescription","Restore only the missing facial patch in the south view.");
            var views=json.createArrayNode();for(String d:List.of("south","north","west","east")){
                var v=json.createObjectNode().put("direction",d).put("confidence",.95);
                for(String f:List.of("identityMatches","eyesReadable","styleMatches","directionCorrect","tailPlausible"))v.put(f,true);
                v.set("issues",json.valueToTree(!pass && d.equals("south")?List.of("COAT_MISMATCH"):List.of()));views.add(v);
            }
            r.set("propertyReview",json.valueToTree(Map.of("views",views,"tailConsistent",true)));return r;});
        clearInvocations(provider);post(subject,path(id)+"/seed-repair-resume",body,200);post(subject,path(id)+"/seed-repair-resume",body,200);
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(step(id,"character").path("repairCount").asInt()).isEqualTo(2);
        assertThat(read(id).at("/qualityPolicy/maxSeedRepairs")).isEqualTo(limit);
        for(String d:List.of("north","west","east"))assertThat(step(id,"character").at("/result/hashes/"+d)).isEqualTo(original.at("/hashes/"+d));
        verify(provider,times(1)).editSeeds(any());verify(provider,times(12)).submit(eq(false),any());
        clearInvocations(provider);post(subject,path(id)+"/seed-repair-resume",body,200);tick();verifyNoInteractions(provider);
        var conflict=new HashMap<>(body);conflict.put("note","Changed request must not restart the same completion or spend again");post(subject,path(id)+"/seed-repair-resume",conflict,409);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"exhausted","stale-seed","stale-rules","motion-started","unauthorized","new-request"})
    void seedResumeRejectsUnsafeOrDuplicateContinuations(String failure)throws Exception {
        UUID id=repairedSeedHold(failure.equals("exhausted")?3:1);var body=seedResumeBody(id);UUID who=subject;int expected=409;
        switch(failure){
            case "stale-seed" -> body.put("expectedSeedHashes",Map.of("south","0".repeat(64),"north","0".repeat(64),"west","0".repeat(64),"east","0".repeat(64)));
            case "stale-rules" -> body.put("expectedRulesSha256","1".repeat(64));
            case "motion-started" -> jdbc.update("UPDATE shelter.styled_asset_steps SET submitted_at=now() WHERE job_id=? AND label='walk-south'",id);
            case "unauthorized" -> {who=UUID.randomUUID();expected=403;}
            case "new-request" -> {post(subject,path(id)+"/seed-repair-resume",body,200);body.put("requestId",UUID.randomUUID().toString());}
        }
        clearInvocations(provider);post(who,path(id)+"/seed-repair-resume",body,expected);verifyNoInteractions(provider);
    }
    @Test void uncertainCoatAfterResumeDoesNotSpendLearnOrApprove()throws Exception {
        UUID id=repairedSeedHold(1);var body=seedResumeBody(id);
        when(seedQuality.reviewRecovery(any(),anyList(),any(),any())).thenAnswer(c->{outsideTransaction();
            var r=(tools.jackson.databind.node.ObjectNode)automaticSeedReport(c.getArgument(1),false);r.put("recoveryVersion","photo-grounded-recovery-v1").put("appearance","UNCERTAIN");
            r.putObject("propertyReview").putArray("views").addObject().put("direction","south").put("coatObservationUncertain",true);return r;});
        int examples=jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=?",Integer.class,id);
        clearInvocations(provider);post(subject,path(id)+"/seed-repair-resume",body,200);tick();
        assertThat(read(id).path("failureCode").asText()).isEqualTo("SEED_OBSERVATION_UNCERTAIN");
        assertThat(step(id,"character").path("repairCount").asInt()).isEqualTo(1);verifyNoInteractions(provider);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=?",Integer.class,id)).isEqualTo(examples);
    }
    UUID failedRepairedSeedRecheck()throws Exception {
        UUID id=repairedSeedHold(1);var body=seedRecheckBody(id);
        post(subject,path(id)+"/quality-recheck",body,200);
        // Replay the recorded live v18 failure: CHECKING was treated as another edit before vision.
        jdbc.update("UPDATE shelter.styled_asset_steps SET status='FAILED' WHERE job_id=? AND action='BASE'",id);
        jdbc.update("UPDATE shelter.asset_jobs SET status='FAILED',failure_code='RECOVERY_INPUT_CHANGED' WHERE id=?",id);
        return id;
    }
    @Test void correctedDeploymentCanRecheckTheRecordedFailureWithoutResettingPaidHistory()throws Exception {
        UUID id=failedRepairedSeedRecheck();var before=step(id,"character");var body=seedRecheckBody(id);
        var history=jdbc.queryForObject("SELECT jsonb_array_length(attempt_history) FROM shelter.styled_asset_steps WHERE job_id=? AND action='BASE'",Integer.class,id);
        clearInvocations(provider,seedQuality);
        post(UUID.randomUUID(),path(id)+"/quality-recheck",body,403);
        var stale=new HashMap<>(body);stale.put("expectedSeedHashes",Map.of());post(subject,path(id)+"/quality-recheck",stale,409);
        stale=new HashMap<>(body);stale.put("expectedRulesSha256","1".repeat(64));post(subject,path(id)+"/quality-recheck",stale,409);
        post(subject,path(id)+"/quality-recheck",body,200);post(subject,path(id)+"/quality-recheck",body,200);tick();
        assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");
        assertThat(step(id,"character").path("result")).isEqualTo(before.path("result"));
        assertThat(step(id,"character").path("repairCount").asInt()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(attempt_history) FROM shelter.styled_asset_steps WHERE job_id=? AND action='BASE'",Integer.class,id)).isEqualTo(history+1);
        verify(seedQuality).reviewRecovery(any(),anyList(),any(),any());verifyNoInteractions(provider);
        assertThat(read(id).path("seedReview").isNull()).isTrue();publicStatus(404);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"other-failure","missing-history","different-count","different-result","missing-rule-origin"})
    void failedRecheckResumeRejectsUnrelatedOrChangedEvidence(String defect)throws Exception {
        UUID id=failedRepairedSeedRecheck();var body=seedRecheckBody(id);
        switch(defect){
            case "other-failure" -> jdbc.update("UPDATE shelter.asset_jobs SET failure_code='PROVIDER_JOB_FAILED' WHERE id=?",id);
            case "missing-history" -> jdbc.update("UPDATE shelter.styled_asset_steps SET attempt_history='[]'::jsonb WHERE job_id=? AND action='BASE'",id);
            case "different-count" -> jdbc.update("UPDATE shelter.styled_asset_steps SET repair_count=2 WHERE job_id=? AND action='BASE'",id);
            case "different-result" -> jdbc.update("UPDATE shelter.styled_asset_steps SET result=result || '{\"tampered\":true}'::jsonb WHERE job_id=? AND action='BASE'",id);
            case "missing-rule-origin" -> jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=quality_policy-'recheckFromRulesSha256' WHERE id=?",id);
        }
        clearInvocations(provider,seedQuality);post(subject,path(id)+"/quality-recheck",body,409);tick();
        assertThat(read(id).path("status").asText()).isEqualTo("FAILED");verifyNoInteractions(provider,seedQuality);
    }
    @Test void repairedSeedRecheckPassCanReachSystemApprovalWithoutAnotherBaseGeneration()throws Exception {
        UUID id=repairedSeedHold(1);var body=seedRecheckBody(id);var original=step(id,"character").path("result");
        when(seedQuality.reviewRecovery(any(),anyList(),any(),any())).thenAnswer(c->{outsideTransaction();
            var r=(tools.jackson.databind.node.ObjectNode)automaticSeedReport(c.getArgument(1),true);
            return r.put("recoveryVersion","photo-grounded-recovery-v1").put("appearance","PASS");});
        clearInvocations(provider);post(subject,path(id)+"/quality-recheck",body,200);finish(id);
        assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(read(id).at("/seedReview/actor").asText()).isEqualTo("SYSTEM");
        assertThat(read(id).at("/qualityApproval/actor").asText()).isEqualTo("SYSTEM");
        assertThat(step(id,"character").path("result")).isEqualTo(original);
        verify(provider,never()).submit(eq(true),any());verify(provider,never()).editSeeds(any());
        verify(provider,times(12)).submit(eq(false),any());
    }
    @Test void failedRecheckRequiresANewRuleVersionBeforeRetry()throws Exception {
        UUID id=failedRepairedSeedRecheck();var j=read(id);
        var body=Map.of("note","A repeated same-policy request must not restart the failed audit",
            "expectedSeedHashes",j.at("/steps/0/result/hashes"),"expectedRulesSha256",j.at("/qualityPolicy/rulesSha256").asText());
        clearInvocations(provider,seedQuality);post(subject,path(id)+"/quality-recheck",body,409);tick();verifyNoInteractions(provider,seedQuality);
    }
    @Test void repairedSeedRecheckStillRejectsChangedPixelsBeforeVision()throws Exception {
        UUID id=repairedSeedHold(1);var body=seedRecheckBody(id);
        post(subject,path(id)+"/quality-recheck",body,200);
        objects.put(step(id,"character").at("/result/keys/south").asText(),png);
        clearInvocations(provider,seedQuality);tick();
        assertThat(read(id).path("failureCode").asText()).isEqualTo("STYLED_SEED_CHANGED");verifyNoInteractions(provider,seedQuality);
    }
    @Test void recoveryBudgetExhaustionCannotStartMotionOrPublishFailedBase()throws Exception {
        UUID id=recoveryRequest(99,false);finish(id);assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");
        assertThat(step(id,"character").path("repairCount").asInt()).isEqualTo(3);
        verify(provider,times(3)).editSeeds(any());verify(provider,never()).submit(eq(false),any());publicStatus(404);
        review(id,true,"APPROVE",409);clearInvocations(provider);for(int i=0;i<3;i++)tick();verifyNoInteractions(provider);
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(attempt_history) FROM shelter.styled_asset_steps WHERE job_id=? AND label='character'",Integer.class,id)).isEqualTo(3);
    }
    @Test void selectedRepairArchivesOnlyFailedDirectionAndPreservesPassingStorageHashes()throws Exception {
        UUID id=recoveryRequest(1,false);finish(id);assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(read(id).at("/qualityPolicy/seedRepairVersion").asText()).isEqualTo("selected-seed-repair-v1");
        var previous=json.readTree(jdbc.queryForObject("SELECT attempt_history->0 FROM shelter.styled_asset_steps WHERE job_id=? AND label='character'",String.class,id));
        var current=step(id,"character").path("result");
        for(String d:List.of("north","west","east")) {
            assertThat(current.at("/hashes/"+d)).isEqualTo(previous.at("/result/hashes/"+d));
            assertThat(objects.get(current.at("/keys/"+d).asText())).isEqualTo(objects.get(previous.at("/result/keys/"+d).asText()));
        }
        assertThat(current.at("/selectedRepair/plan/directions")).isEqualTo(json.valueToTree(List.of("south")));
        var captured=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(provider).editSeeds(captured.capture());
        assertThat(captured.getValue().path("edit_images").size()).isEqualTo(1);
        verify(provider).pollSeeds(any(),eq(List.of("south")));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={true,false})
    void boundedFaceRepairIsArchivedAndUncertainLocalizationHoldsWithoutPurchase(boolean confident)throws Exception {
        UUID id=recoveryRequest(0,false);var reviews=new java.util.concurrent.atomic.AtomicInteger();
        when(seedQuality.reviewRecovery(any(),anyList(),any(),any())).thenAnswer(c->{outsideTransaction();boolean passed=reviews.getAndIncrement()>0;
            var r=(tools.jackson.databind.node.ObjectNode)automaticSeedReport(c.getArgument(1),passed);r.put("recoveryVersion","photo-grounded-recovery-v1");
            r.put("appearance",passed?"PASS":"FAIL");r.put("repairDescription","Use a short neutral mouth line, retaining the white fur.");
            var views=json.createArrayNode();for(String d:List.of("south","north","west","east"))views.add(json.valueToTree(Map.of("direction",d,"confidence",.95,
                "identityMatches",true,"eyesReadable",true,"styleMatches",true,"directionCorrect",true,"tailPlausible",true,
                "issues",!passed && d.equals("south")?List.of("MOUTH_EXPRESSION"):List.of())));
            r.set("propertyReview",json.valueToTree(Map.of("views",views,"tailConsistent",true)));return r;});
        when(eyeAi.structuredImage(anyString(),anyString(),any(),anyMap())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("confident",confident,"regions",List.of(Map.of("direction","south","x",14,"y",14,"width",3,"height",2)),"note","fixture"));});
        when(provider.editSeedEyes(any())).thenAnswer(c->{outsideTransaction();return UUID.randomUUID();});
        when(provider.pollSeedEyes(any())).thenAnswer(c->{outsideTransaction();var raw=new BufferedImage(128,32,BufferedImage.TYPE_INT_ARGB);var g=raw.createGraphics();for(int i=0;i<4;i++)g.drawImage(ImageIO.read(new ByteArrayInputStream(png)),i*32,0,null);g.dispose();raw.setRGB(15,14,0xff332211);raw.setRGB(80,25,0xff221133);
            var bytes=new ByteArrayOutputStream();ImageIO.write(raw,"png",bytes);return json.valueToTree(Map.of("status","COMPLETED","eyeSheet",Base64.getEncoder().encodeToString(bytes.toByteArray())));});
        finish(id);var j=read(id);verify(provider,never()).editSeeds(any());
        if(confident){assertThat(j.path("status").asText()).isEqualTo("APPROVED");verify(provider).editSeedEyes(any());
            var metadata=step(id,"character").path("result");assertThat(metadata.at("/selectedRepair/rawOutsideMaskDifferences").asInt()).isEqualTo(1);
            String key=metadata.at("/selectedRepair/rawKeys/strip").asText();assertThat(objects).containsKey(key);
            assertThat(metadata.at("/selectedRepair/rawHashes/strip").asText()).hasSize(64);
            assertThat(step(id,"character").path("repairCount").asInt()).isEqualTo(1);
        }else{assertThat(j.path("status").asText()).isEqualTo("SEED_REVIEW");verify(provider,never()).editSeedEyes(any());verify(provider,never()).submit(eq(false),any());
            assertThat(step(id,"character").at("/qualityReport/seedRepairPlan/status").asText()).isEqualTo("UNCERTAIN");}
    }
    @Test void oldRecoveryJobsKeepTheOriginalFourViewProtocol()throws Exception {
        UUID id=recoveryRequest(1,false);jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=quality_policy-'seedRepairVersion' WHERE id=?",id);
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
        verify(provider).pollSeeds(any());verify(provider,never()).pollSeeds(any(),anyList());
    }
    @Test void unresolvedSeedSelectionHoldsWithoutSpendingOrResettingHistory()throws Exception {
        UUID id=recoveryRequest(99,false);
        when(seedQuality.reviewRecovery(any(),anyList(),any(),any())).thenAnswer(c->{var r=(tools.jackson.databind.node.ObjectNode)automaticSeedReport(c.getArgument(1),false);r.put("recoveryVersion","photo-grounded-recovery-v1");return r;});
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("SEED_REVIEW");
        assertThat(step(id,"character").at("/qualityReport/seedRepairPlan/status").asText()).isEqualTo("UNRESOLVED_SELECTION");
        assertThat(step(id,"character").path("repairCount").asInt()).isZero();verify(provider,never()).editSeeds(any());verify(provider,never()).submit(eq(false),any());
    }
    @Test void uncertainPaidBaseEditIsHeldWithoutDuplicateSubmission()throws Exception {
        UUID id=recoveryRequest(1,false);when(provider.editSeeds(any())).thenThrow(new AssetProvider.Failure("PIXELLAB_CONNECTION",true));
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("OUTCOME_UNKNOWN");
        verify(provider,times(1)).editSeeds(any());clearInvocations(provider);for(int i=0;i<3;i++)tick();verifyNoInteractions(provider);publicStatus(404);
    }
    Map<String,Object> input() {return Map.of("photoId",photo,"traits",Map.of("sourcePhotoSha256","a".repeat(64),"faceBox",List.of(.1,.1,.8,.8),"identityDescription","brown dog","motionDescription","brown dog","rearDescription","unknown markings","seed",42,"reviewNote","Reviewed full body photo and face crop for this dog"));}
    // Existing cases exercise persisted pre-B63 jobs and their explicit manual review contract.
    UUID request() throws Exception {
        UUID id=UUID.fromString(post(subject,"/v1/shelter-admin/dogs/"+dog+"/styled-assets",input(),202).at("/data/id").asText());
        jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=(quality_policy-'seedTailEvidenceVersion'-'automaticApproval'-'seedMotionMargin'-'lessonRevision'-'seedEyeRepair'-'recoveryVersion'-'motionFrameSize'-'maxSeedRepairs') || '{\"maxRepairsPerClip\":2,\"learningRecovery\":\"validated-motion-learning-v2\"}'::jsonb WHERE id=?",id);return id;
    }
    String path(UUID id) {return "/v1/shelter-admin/dogs/"+dog+"/styled-assets/"+id;}
    JsonNode read(UUID id) throws Exception {return get(subject,path(id),200).path("data");}
    void review(UUID id,boolean seed,String decision,int expected) throws Exception {post(subject,path(id)+(seed?"/seed-review":"/review"),Map.of("decision",decision,"note","Reviewed all directions, identity and motion quality","expectedSeedHashes",read(id).at("/steps/0/result/hashes")),expected);}
    void finish(UUID id) throws Exception {for(int i=0;i<220 && !Set.of("REVIEW","FAILED","OUTCOME_UNKNOWN","APPROVED","SEED_REVIEW","REJECTED","CANCELLED").contains(read(id).path("status").asText());i++)tick();}
    void tick() {jdbc.update("UPDATE shelter.asset_jobs SET next_run_at=now() WHERE dog_id=?",dog);worker.tick();}
    void publicStatus(int status) throws Exception {get(null,"/v1/dogs/"+dog+"/assets",status);}
    String bearer(UUID subject) {return "Bearer "+tokens.token(subject);}
    JsonNode post(UUID subject,String path,Object body,int status) throws Exception {var req=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).contentType("application/json").content(json.writeValueAsBytes(body));if(subject!=null)req.header("Authorization",bearer(subject));var result=mvc.perform(req).andExpect(status().is(status)).andReturn();return json.readTree(result.getResponse().getContentAsString());}
    JsonNode get(UUID subject,String path,int status) throws Exception {var req=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);if(subject!=null)req.header("Authorization",bearer(subject));var result=mvc.perform(req).andExpect(status().is(status)).andReturn();return json.readTree(result.getResponse().getContentAsString());}
    void outsideTransaction() {assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
    Map<String,Object> responseResumeFixture(UUID id)throws Exception {
        // Synthetic legacy format-failure fixture; never rewrites any live job.
        jdbc.update("UPDATE shelter.asset_jobs SET status='FAILED',failure_code='QUALITY_MOTION_RESPONSE_INVALID',quality_approval=NULL WHERE id=?",id);
        jdbc.update("UPDATE shelter.styled_asset_steps SET status='FAILED',quality_report='{\"status\":\"STARTED\"}'::jsonb WHERE job_id=? AND label='idle-south'",id);
        return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"note","Resume only the stored malformed review without changing passed clips or buying a generation",
            "expectedSeedHashes",read(id).at("/steps/0/result/hashes"),"expectedSheetSha256",step(id,"idle-south").at("/result/sha256").asText(),
            "expectedRulesSha256",currentRules(),"label","idle-south"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"lower","upper","legacy-upper"})
    void responseResumePreservesEveryOtherReportBudgetAndStoredImage(String spelling)throws Exception {
        UUID id=b75HeldPack();var body=responseResumeFixture(id);var before=read(id);var saved=new HashMap<>(objects);
        if(!spelling.equals("lower"))body.put("requestId",body.get("requestId").toString().toUpperCase(Locale.ROOT));
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("south"))).thenAnswer(c->b75MotionReport(c.getArgument(1),c.getArgument(2),"south",true,"PASS"));
        clearInvocations(provider,quality);post(subject,path(id)+"/quality-response-resume",body,200);
        if(spelling.equals("legacy-upper"))jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=jsonb_set(quality_policy,'{qualityResponseResumes,0,request,requestId}',to_jsonb(CAST(? AS text))) WHERE id=?",body.get("requestId"),id);
        post(subject,path(id)+"/quality-response-resume",body,200);
        body.put("requestId",body.get("requestId").toString().toLowerCase(Locale.ROOT));
        post(subject,path(id)+"/quality-response-resume",body,200);finish(id);
        assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
        for(var s:before.path("steps")) {
            var after=step(id,s.path("label").asText());assertThat(after.path("repairCount")).isEqualTo(s.path("repairCount"));assertThat(after.path("result")).isEqualTo(s.path("result"));
            if(!s.path("label").asText().equals("idle-south"))assertThat(after.path("qualityReport")).isEqualTo(s.path("qualityReport"));
        }
        for(var e:saved.entrySet())assertThat(objects.get(e.getKey())).isEqualTo(e.getValue());
        verifyNoInteractions(provider);verify(quality,times(1)).review(any(),anyList(),anyList(),eq("IDLE"),eq("south"));
        var history=json.readTree(jdbc.queryForObject("SELECT attempt_history FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-south'",String.class,id));
        assertThat(history.toString()).contains("qualityResponseResume","STARTED");
        post(subject,path(id)+"/quality-response-resume",body,200);tick();verifyNoInteractions(provider);
        body.put("note","A changed replay cannot authorize another review of the same source");post(subject,path(id)+"/quality-response-resume",body,409);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"seed","sheet","rules","label","failure","status","verdict","unauthorized","anonymous","revoked","duplicate"})
    void responseResumeRejectsUnsafeOrRepeatedRecovery(String defect)throws Exception {
        UUID id=b75HeldPack();var body=responseResumeFixture(id);UUID actor=subject;int expected=409;
        switch(defect) {
            case "seed" -> body.put("expectedSeedHashes",Map.of("south","1".repeat(64),"north","1".repeat(64),"west","1".repeat(64),"east","1".repeat(64)));
            case "sheet" -> body.put("expectedSheetSha256","1".repeat(64));
            case "rules" -> body.put("expectedRulesSha256","1".repeat(64));
            case "label" -> body.put("label","walk-south");
            case "failure" -> jdbc.update("UPDATE shelter.asset_jobs SET failure_code='QUALITY_AI_TIMEOUT' WHERE id=?",id);
            case "status" -> jdbc.update("UPDATE shelter.asset_jobs SET status='REVIEW' WHERE id=?",id);
            case "verdict" -> jdbc.update("UPDATE shelter.styled_asset_steps SET quality_report='{\"passed\":false}'::jsonb WHERE job_id=? AND label='idle-south'",id);
            case "unauthorized" -> {actor=UUID.randomUUID();expected=403;}
            case "anonymous" -> {actor=null;expected=401;}
            case "revoked" -> jdbc.update("UPDATE shelter.asset_source_permissions SET revoked_at=now() WHERE id=?",permission);
            case "duplicate" -> {post(subject,path(id)+"/quality-response-resume",body,200);body.put("requestId",UUID.randomUUID().toString());}
        }
        clearInvocations(provider);post(actor,path(id)+"/quality-response-resume",body,expected);verifyNoInteractions(provider);
    }
    int timeoutEvents(UUID id,String label) {
        return jdbc.queryForObject("SELECT count(*) FROM shelter.styled_asset_steps s,jsonb_array_elements(s.attempt_history) a WHERE s.job_id=? AND s.label=? AND a->>'qualityTimeoutRetry'='true'",Integer.class,id,label);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints={1,2,3})
    void qualityTimeoutRetriesSavedMotionWithDurableCapAndDelay(int timeouts)throws Exception {
        UUID id=recoveryRequest(0,false);var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("west"))).thenAnswer(c->{outsideTransaction();
            if(calls.getAndIncrement()<timeouts)throw StyledQualityFailureFixture.failure("QUALITY_AI_TIMEOUT");
            return b75MotionReport(c.getArgument(1),c.getArgument(2),"west",true,"PASS");});
        for(int i=0;i<30 && timeoutEvents(id,"idle-west")==0;i++)tick();
        assertThat(timeoutEvents(id,"idle-west")).isEqualTo(1);
        var cached=jdbc.queryForObject("SELECT provider_result::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-west'",String.class,id);
        assertThat(cached).contains("COMPLETED","frames");
        assertThat(jdbc.queryForObject("SELECT next_run_at>now()+interval '10 seconds' AND lease_token IS NULL FROM shelter.asset_jobs WHERE id=?",Boolean.class,id)).isTrue();
        worker.tick();assertThat(calls.get()).isEqualTo(1); // Backoff survives a new worker tick.
        finish(id);assertThat(calls.get()).isEqualTo(Math.min(timeouts+1,3));
        assertThat(timeoutEvents(id,"idle-west")).isEqualTo(Math.min(timeouts,2));
        assertThat(step(id,"idle-west").path("repairCount").asInt()).isZero();verify(provider,never()).editAnimation(any());
        if(timeouts<3) {
            assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");verify(provider,times(12)).submit(eq(false),any());
        } else {
            assertThat(read(id).path("failureCode").asText()).isEqualTo("QUALITY_AI_TIMEOUT");
            assertThat(jdbc.queryForObject("SELECT provider_result::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-west'",String.class,id)).isEqualTo(cached);
            var body=timeoutResumeBody(id);post(subject,path(id)+"/quality-timeout-resume",body,409);tick();assertThat(calls.get()).isEqualTo(3);
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM shelter.styled_quality_examples WHERE job_id=? AND NOT passed",Integer.class,id)).isZero();
    }
    @Test void qualityTimeoutRetriesBaseWithoutResubmittingCharacter()throws Exception {
        UUID id=recoveryRequest(0,false);var original=org.mockito.Mockito.mockingDetails(seedQuality).getStubbings().stream().filter(s->s.getInvocation().getMethod().getName().equals("reviewRecovery")).findFirst().orElseThrow();
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        doAnswer(c->{outsideTransaction();if(calls.getAndIncrement()==0)throw StyledQualityFailureFixture.failure("QUALITY_AI_TIMEOUT");return original.answer(c);})
            .when(seedQuality).reviewRecovery(any(),anyList(),any(),any());
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(timeoutEvents(id,"character")).isEqualTo(1);verify(provider,times(1)).submit(eq(true),any());verify(provider,never()).editSeeds(any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"QUALITY_AI_AUTH_FAILED","QUALITY_AI_UNAVAILABLE","QUALITY_MOTION_RESPONSE_INVALID","QUALITY_REVIEW_INTERRUPTED"})
    void qualityTimeoutDoesNotRetryOtherErrors(String code)throws Exception {
        UUID id=recoveryRequest(0,false);
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("west"))).thenThrow(StyledQualityFailureFixture.failure(code));
        finish(id);assertThat(read(id).path("failureCode").asText()).isEqualTo(code);assertThat(timeoutEvents(id,"idle-west")).isZero();
        verify(quality,times(1)).review(any(),anyList(),anyList(),eq("IDLE"),eq("west"));
    }
    @Test void qualityTimeoutRetriesCheckingResultWithoutProviderCalls()throws Exception {
        UUID id=recoveryRequest(0,false);finish(id);var before=step(id,"idle-west").path("result");
        jdbc.update("UPDATE shelter.asset_jobs SET status='QUEUED',quality_approval=NULL WHERE id=?",id);
        jdbc.update("UPDATE shelter.styled_asset_steps SET status='CHECKING',quality_report=NULL WHERE job_id=? AND label='idle-west'",id);
        var calls=new java.util.concurrent.atomic.AtomicInteger();
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("west"))).thenAnswer(c->{
            if(calls.getAndIncrement()==0)throw StyledQualityFailureFixture.failure("QUALITY_AI_TIMEOUT");return b75MotionReport(c.getArgument(1),c.getArgument(2),"west",true,"PASS");});
        clearInvocations(provider);finish(id);verifyNoInteractions(provider);
        assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");assertThat(step(id,"idle-west").path("result")).isEqualTo(before);
        assertThat(timeoutEvents(id,"idle-west")).isEqualTo(1);
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"revoked","lease-lost","missing-checkpoint","not-started"})
    void qualityTimeoutCannotRetryWithoutOwnedAuthorizedCheckpoint(String defect)throws Exception {
        UUID id=recoveryRequest(0,false);
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("west"))).thenAnswer(c->{
            switch(defect) {
                case "revoked" -> jdbc.update("UPDATE shelter.asset_source_permissions SET revoked_at=now() WHERE id=?",permission);
                case "lease-lost" -> jdbc.update("UPDATE shelter.asset_jobs SET lease_token=NULL WHERE id=?",id);
                case "missing-checkpoint" -> jdbc.update("UPDATE shelter.styled_asset_steps SET provider_result=NULL,result=NULL WHERE job_id=? AND label='idle-west'",id);
                case "not-started" -> jdbc.update("UPDATE shelter.styled_asset_steps SET quality_report=NULL WHERE job_id=? AND label='idle-west'",id);
            }
            throw StyledQualityFailureFixture.failure("QUALITY_AI_TIMEOUT");});
        for(int i=0;i<15 && !step(id,"idle-west").path("status").asText().equals("FAILED") && !read(id).path("status").asText().equals("CANCELLED");i++) {
            tick();if(defect.equals("lease-lost") && jdbc.queryForObject("SELECT provider_result IS NOT NULL FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-west'",Boolean.class,id))break;
        }
        assertThat(timeoutEvents(id,"idle-west")).isZero();verify(provider,never()).editAnimation(any());
        if(defect.equals("revoked"))assertThat(read(id).path("status").asText()).isEqualTo("CANCELLED");
    }
    UUID timeoutLegacyFixture(boolean heldSouth)throws Exception {
        UUID id=recoveryRequest(0,false);
        jdbc.update("UPDATE shelter.asset_jobs SET quality_policy=quality_policy-'qualityTimeoutVersion' WHERE id=?",id);
        if(heldSouth)when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("south"))).thenAnswer(c->b75MotionReport(c.getArgument(1),c.getArgument(2),"south",false,"UNCERTAIN"));
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("west"))).thenThrow(StyledQualityFailureFixture.failure("QUALITY_AI_TIMEOUT"));
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("FAILED");
        assertThat(step(id,"idle-west").path("result").isNull()).isTrue();assertThat(timeoutEvents(id,"idle-west")).isZero();return id;
    }
    Map<String,Object> timeoutResumeBody(UUID id)throws Exception {
        var cp=get(subject,path(id)+"/quality-timeout-checkpoint",200).path("data");
        assertThat(cp.toString()).doesNotContain("base64","frames","providerJobId","storage");
        return new HashMap<>(Map.of("requestId",UUID.randomUUID().toString(),"note","Resume the exact saved timeout checkpoint without new provider submission or reset",
            "label",cp.path("label").asText(),"expectedCheckpointSha256",cp.path("checkpointSha256").asText(),"expectedSeedHashes",cp.path("seedHashes"),
            "expectedRulesSha256",cp.path("rulesSha256").asText(),"expectedSheetHashes",Map.of()));
    }
    @Test void qualityTimeoutResumeKeepsOriginalCheckpointAndIsIdempotent()throws Exception {
        UUID id=timeoutLegacyFixture(false);var body=timeoutResumeBody(id);var before=read(id);var saved=new HashMap<>(objects);
        var providerId=jdbc.queryForObject("SELECT provider_job_id FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-west'",UUID.class,id);
        clearInvocations(provider);doAnswer(c->b75MotionReport(c.getArgument(1),c.getArgument(2),"west",true,"PASS")).when(quality).review(any(),anyList(),anyList(),eq("IDLE"),eq("west"));
        body.put("requestId",body.get("requestId").toString().toUpperCase(Locale.ROOT));post(subject,path(id)+"/quality-timeout-resume",body,200);
        body.put("requestId",body.get("requestId").toString().toLowerCase(Locale.ROOT));post(subject,path(id)+"/quality-timeout-resume",body,200);
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");
        assertThat(jdbc.queryForObject("SELECT provider_job_id FROM shelter.styled_asset_steps WHERE job_id=? AND label='idle-west'",UUID.class,id)).isEqualTo(providerId);
        for(var s:before.path("steps"))if(s.path("status").asText().equals("SUCCEEDED"))assertThat(step(id,s.path("label").asText())).isEqualTo(s);
        for(var e:saved.entrySet())assertThat(objects.get(e.getKey())).isEqualTo(e.getValue());
        verify(provider,times(9)).submit(eq(false),any());verify(provider,never()).editAnimation(any());assertThat(timeoutEvents(id,"idle-west")).isEqualTo(1);
        clearInvocations(provider);post(subject,path(id)+"/quality-timeout-resume",body,200);tick();verifyNoInteractions(provider);
        body.put("note","Changed replay cannot authorize another attempt of this same checkpoint");post(subject,path(id)+"/quality-timeout-resume",body,409);
    }
    @Test void qualityTimeoutResumeNewRulesAuditsAllCompletedStepsAndUsesOnlySelectedRemainingBudget()throws Exception {
        UUID id=timeoutLegacyFixture(true);b75ResumeBody(id);var body=timeoutResumeBody(id);var before=read(id);var calls=new java.util.concurrent.atomic.AtomicInteger();
        body.put("expectedSheetHashes",Map.of("idle-south",step(id,"idle-south").at("/result/sha256").asText()));
        when(quality.review(any(),anyList(),anyList(),eq("IDLE"),eq("south"))).thenAnswer(c->{boolean pass=calls.getAndIncrement()>0;return b75MotionReport(c.getArgument(1),c.getArgument(2),"south",pass,pass?"PASS":"CONFIRMED_DEFECT");});
        doAnswer(c->b75MotionReport(c.getArgument(1),c.getArgument(2),"west",true,"PASS")).when(quality).review(any(),anyList(),anyList(),eq("IDLE"),eq("west"));
        clearInvocations(provider,seedQuality);post(subject,path(id)+"/quality-timeout-resume",body,200);
        assertThat(read(id).path("seedReview").isNull()).isTrue();assertThat(step(id,"character").path("status").asText()).isEqualTo("CHECKING");
        finish(id);assertThat(read(id).path("status").asText()).isEqualTo("APPROVED");verify(provider,times(1)).editAnimation(any());verify(provider,times(9)).submit(eq(false),any());
        assertThat(step(id,"idle-south").path("repairCount").asInt()).isEqualTo(1);
        assertThat(step(id,"idle-west").path("repairCount").asInt()).isZero();
        assertThat(read(id).at("/qualityPolicy/maxRepairsPerClip")).isEqualTo(before.at("/qualityPolicy/maxRepairsPerClip"));
        for(String label:List.of("character","idle-north")) {
            var old=before.path("steps").valueStream().filter(s->s.path("label").asText().equals(label)).findFirst().orElseThrow();
            assertThat(step(id,label).path("result")).isEqualTo(old.path("result"));
            assertThat(jdbc.queryForObject("SELECT attempt_history::text FROM shelter.styled_asset_steps WHERE job_id=? AND label=?",String.class,id,label)).contains("qualityTimeoutRulesRecheck");
        }
        verify(seedQuality,times(1)).reviewRecovery(any(),anyList(),any(),any());
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings={"seed","checkpoint","rules","label","failure","status","missing","report","unauthorized","anonymous","revoked","duplicate","budget","passing","extra-field"})
    void qualityTimeoutResumeRejectsStaleUnsafeOrDuplicateRequests(String defect)throws Exception {
        UUID id=timeoutLegacyFixture(true);var body=timeoutResumeBody(id);UUID actor=subject;int expected=409;
        switch(defect) {
            case "seed" -> body.put("expectedSeedHashes",Map.of("south","1".repeat(64),"north","1".repeat(64),"west","1".repeat(64),"east","1".repeat(64)));
            case "checkpoint" -> body.put("expectedCheckpointSha256","1".repeat(64));
            case "rules" -> body.put("expectedRulesSha256","1".repeat(64));
            case "label" -> body.put("label","walk-west");
            case "failure" -> jdbc.update("UPDATE shelter.asset_jobs SET failure_code='QUALITY_AI_AUTH_FAILED' WHERE id=?",id);
            case "status" -> jdbc.update("UPDATE shelter.asset_jobs SET status='REVIEW' WHERE id=?",id);
            case "missing" -> jdbc.update("UPDATE shelter.styled_asset_steps SET provider_result=NULL WHERE job_id=? AND label='idle-west'",id);
            case "report" -> jdbc.update("UPDATE shelter.styled_asset_steps SET quality_report='{\"passed\":false}'::jsonb WHERE job_id=? AND label='idle-west'",id);
            case "unauthorized" -> {actor=UUID.randomUUID();expected=403;}
            case "anonymous" -> {actor=null;expected=401;}
            case "revoked" -> jdbc.update("UPDATE shelter.asset_source_permissions SET revoked_at=now() WHERE id=?",permission);
            case "duplicate" -> {post(subject,path(id)+"/quality-timeout-resume",body,200);body.put("requestId",UUID.randomUUID().toString());}
            case "budget" -> {b75ResumeBody(id);body=timeoutResumeBody(id);body.put("expectedSheetHashes",Map.of("idle-south",step(id,"idle-south").at("/result/sha256").asText()));jdbc.update("UPDATE shelter.styled_asset_steps SET repair_count=3 WHERE job_id=? AND label='idle-south'",id);}
            case "passing" -> {b75ResumeBody(id);body=timeoutResumeBody(id);body.put("expectedSheetHashes",Map.of("idle-north",step(id,"idle-north").at("/result/sha256").asText()));}
            case "extra-field" -> {body.put("resetBudget",true);expected=400;}
        }
        clearInvocations(provider,quality);post(actor,path(id)+"/quality-timeout-resume",body,expected);verifyNoInteractions(provider,quality);
        if(defect.equals("unauthorized") || defect.equals("anonymous"))get(actor,path(id)+"/quality-timeout-checkpoint",expected);
    }
}
