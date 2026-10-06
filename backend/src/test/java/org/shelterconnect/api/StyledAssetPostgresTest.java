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
    @MockitoBean StyledLessonAgent lessonAgent;
    @MockitoBean StyledAssetProvider provider;@MockitoBean AssetStorage storage;
    @MockitoBean StyledSpriteCodec codec;@MockitoBean BehaviorSuggestionProvider suggestions;
    @MockitoBean StyledQualityAgent quality;
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
        when(codec.character(any(),any(),any())).thenAnswer(c->{outsideTransaction();return json.readTree("{\"character\":true}");});
        when(codec.motion(any(),anyString(),anyString(),any())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("action",c.getArgument(1),"direction",c.getArgument(2)));});
        when(codec.motion(any(),anyString(),anyString(),any(),any())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("action",c.getArgument(1),"direction",c.getArgument(2),"quality",c.getArgument(4)));});
        when(quality.contract(any(),any())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("tailCarriage","LOW","version",StyledQualityAgent.VERSION));});
        when(quality.review(any(),anyList(),anyList(),anyString(),anyString())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("passed",true,"issues",List.of()));});
        when(provider.submit(anyBoolean(),any())).thenAnswer(c->{outsideTransaction();return UUID.randomUUID();});
        when(provider.poll(any(),eq(true))).thenAnswer(c->{outsideTransaction();String b=Base64.getEncoder().encodeToString(png);return json.valueToTree(Map.of("status","COMPLETED","directions",Map.of("south",b,"north",b,"west",b,"east",b)));});
        when(provider.poll(any(),eq(false))).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("status","COMPLETED","frames",Collections.nCopies(9,Base64.getEncoder().encodeToString(png))));});
        permission=UUID.fromString(post(opSubject,"/v1/operations/asset-permissions",Map.of("shelterId",shelter,"sourceKey","test-"+dog,"sourceKind","SHELTER","permissionNote","disposable fixture","crawlAllowed",false,"derivativesAllowed",true,"pixellabAllowed",true,"autoGenerate",false),201).at("/data/id").asText());
        post(opSubject,"/v1/operations/asset-imports",Map.of("photoId",photo,"permissionId",permission),200);
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
        finish(id);verify(provider,times(15)).submit(anyBoolean(),any());
        assertThat(read(id).path("failureCode").asText()).isEqualTo("QUALITY_REPAIR_EXHAUSTED");
        post(subject,path(id)+"/quality-recheck",body,200);tick();verify(provider,times(15)).submit(anyBoolean(),any());
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
    @Test void unknownTailKeepsExistingBoundedRegenerationAndNeverBuysEdit() throws Exception {
        when(quality.contract(any(),any())).thenReturn(json.valueToTree(Map.of("tailCarriage","UNKNOWN")));
        when(quality.review(any(),anyList(),anyList(),eq("TAIL_WAG"),eq("west"))).thenReturn(json.valueToTree(Map.of("passed",false,"issues",List.of("CANVAS_CLIPPING"))));
        UUID id=tailPlan();tick();tick();review(id,true,"APPROVE",200);finish(id);verify(provider,never()).editAnimation(any());
        verify(provider,times(19)).submit(anyBoolean(),any());review(id,false,"APPROVE",409);
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
        var realCodec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        doAnswer(c->{var payload=realCodec.motion(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3),c.getArgument(4));
            if(!c.<JsonNode>getArgument(4).path("lessons").isEmpty())lessonStore.disable(opSubject,lesson,json.valueToTree(Map.of("note","Disabled during pending request construction")));
            return payload;}).when(codec).motion(any(),anyString(),anyString(),any(),any());
        clearInvocations(provider);finish(next);
        var calls=org.mockito.ArgumentCaptor.forClass(JsonNode.class);verify(provider,atLeastOnce()).submit(eq(false),calls.capture());
        assertThat(calls.getAllValues().stream().anyMatch(n->n.path("description").asText().contains(learnedPrevention()))).isFalse();
        assertThat(lessonStatus(lesson)).isEqualTo("DISABLED");assertThat(read(next).path("status").asText()).isEqualTo("REVIEW");
    }
    @Test void revokedEvidencePreventsLearningWithoutAnyModelUpload() throws Exception {
        learningPair();UUID lesson=onlyLesson();
        jdbc.update("UPDATE shelter.asset_source_permissions SET revoked_at=now() WHERE id=?",permission);
        lessonWorker.tick();assertThat(lessonStatus(lesson)).isEqualTo("DISABLED");verifyNoInteractions(lessonAgent);
    }
    java.nio.file.Path liveFixtures;
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
        nextLearningDog();UUID next=request();tick();tick();review(next,true,"APPROVE",200);finish(next);
        var pinned=json.readTree(jdbc.queryForObject("SELECT learned_lessons::text FROM shelter.styled_asset_steps WHERE job_id=? AND label='sit-west'",String.class,next));
        assertThat(pinned.get(0).path("id").asText()).isEqualTo(lesson.toString());
        report.set("nextGenerationSnapshot",pinned);report.put("nextPayloadVerified",true);
        java.nio.file.Files.writeString(destination,json.writerWithDefaultPrettyPrinter().writeValueAsString(report));
    }
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
        List<byte[]> seedImages=Collections.nCopies(4,original.getFirst());
        if(liveFixtures!=null) {
            original=sheetFrames(java.nio.file.Files.readAllBytes(liveFixtures.resolve("sheets/sit-left.png")));
            corrected=sheetFrames(java.nio.file.Files.readAllBytes(liveFixtures.resolve("sit-left-adjustment/sit-left.png")));
            seedImages=new ArrayList<>();for(String d:List.of("south","north","west","east"))seedImages.add(java.nio.file.Files.readAllBytes(liveFixtures.resolve("directions/"+d+".png")));
        }
        final var badFrames=original;final var goodFrames=corrected;
        var source=original.getFirst();var motion=new java.util.concurrent.atomic.AtomicReference<>("BASE");
        var attempts=new java.util.concurrent.atomic.AtomicInteger();var checks=new java.util.concurrent.atomic.AtomicInteger();
        var realCodec=new StyledSpriteCodec(json,System.getenv().getOrDefault("ASSET_HARNESS_PYTHON","python3"));
        doAnswer(c->{outsideTransaction();
            motion.set(c.<String>getArgument(1)+"-"+c.<String>getArgument(2));return realCodec.motion(c.getArgument(0),c.getArgument(1),c.getArgument(2),c.getArgument(3),c.getArgument(4));}).when(codec).motion(any(),anyString(),anyString(),any(),any());
        when(provider.poll(any(),eq(true))).thenReturn(json.valueToTree(Map.of("status","COMPLETED","directions",Map.of(
            "south",Base64.getEncoder().encodeToString(seedImages.get(0)),"north",Base64.getEncoder().encodeToString(seedImages.get(1)),
            "west",Base64.getEncoder().encodeToString(seedImages.get(2)),"east",Base64.getEncoder().encodeToString(seedImages.get(3))))));
        when(provider.poll(any(),eq(false))).thenAnswer(c->{outsideTransaction();var frames=motion.get().equals("SIT-west")?
            (attempts.getAndIncrement()==0?badFrames:goodFrames):Collections.nCopies(9,source);
            return json.valueToTree(Map.of("status","COMPLETED","frames",frames.stream().map(Base64.getEncoder()::encodeToString).toList()));});
        when(quality.review(any(),anyList(),anyList(),eq("SIT"),eq("west"))).thenAnswer(c->{outsideTransaction();boolean passed=checks.getAndIncrement()>0;
            return json.valueToTree(Map.of("passed",passed,"issues",passed?List.of():List.of("CANVAS_CLIPPING"),"edgeFrames",passed?List.of():List.of(3,4,5,6,7,8),"note","Recorded tail edge replay"));});
        when(quality.review(any(),anyList(),anyList(),anyString(),anyString(),any())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("passed",true,"issues",List.of()));});
        when(lessonAgent.propose(any(),any())).thenAnswer(c->{outsideTransaction();return json.valueToTree(Map.of("prevention",learnedPrevention(),"criterion","The seated tail tip crosses the right frame boundary during descent or final hold."));});
        when(lessonAgent.replay(any(),any(),anyList())).thenAnswer(c->{outsideTransaction();return replayAnswer(c.getArgument(2),false);});
        UUID id=request();tick();tick();review(id,true,"APPROVE",200);finish(id);
        assertThat(read(id).path("status").asText()).isEqualTo("REVIEW");return id;
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
    Map<String,Object> input() {return Map.of("photoId",photo,"traits",Map.of("sourcePhotoSha256","a".repeat(64),"faceBox",List.of(.1,.1,.8,.8),"identityDescription","brown dog","motionDescription","brown dog","rearDescription","unknown markings","seed",42,"reviewNote","Reviewed full body photo and face crop for this dog"));}
    UUID request() throws Exception {return UUID.fromString(post(subject,"/v1/shelter-admin/dogs/"+dog+"/styled-assets",input(),202).at("/data/id").asText());}
    String path(UUID id) {return "/v1/shelter-admin/dogs/"+dog+"/styled-assets/"+id;}
    JsonNode read(UUID id) throws Exception {return get(subject,path(id),200).path("data");}
    void review(UUID id,boolean seed,String decision,int expected) throws Exception {post(subject,path(id)+(seed?"/seed-review":"/review"),Map.of("decision",decision,"note","Reviewed all directions, identity and motion quality","expectedSeedHashes",read(id).at("/steps/0/result/hashes")),expected);}
    void finish(UUID id) throws Exception {for(int i=0;i<220 && !Set.of("REVIEW","FAILED","OUTCOME_UNKNOWN").contains(read(id).path("status").asText());i++)tick();}
    void tick() {jdbc.update("UPDATE shelter.asset_jobs SET next_run_at=now() WHERE dog_id=?",dog);worker.tick();}
    void publicStatus(int status) throws Exception {get(null,"/v1/dogs/"+dog+"/assets",status);}
    String bearer(UUID subject) {return "Bearer "+tokens.token(subject);}
    JsonNode post(UUID subject,String path,Object body,int status) throws Exception {var result=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(path).header("Authorization",bearer(subject)).contentType("application/json").content(json.writeValueAsBytes(body))).andExpect(status().is(status)).andReturn();return json.readTree(result.getResponse().getContentAsString());}
    JsonNode get(UUID subject,String path,int status) throws Exception {var req=org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path);if(subject!=null)req.header("Authorization",bearer(subject));var result=mvc.perform(req).andExpect(status().is(status)).andReturn();return json.readTree(result.getResponse().getContentAsString());}
    void outsideTransaction() {assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();}
}
