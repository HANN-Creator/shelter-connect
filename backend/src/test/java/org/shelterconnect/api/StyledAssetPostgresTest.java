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
    @MockitoBean StyledAssetProvider provider;@MockitoBean AssetStorage storage;
    @MockitoBean StyledSpriteCodec codec;@MockitoBean BehaviorSuggestionProvider suggestions;
    @MockitoBean StyledQualityAgent quality;
    UUID op,user,opSubject,subject,shelter,dog,photo,permission;byte[] png;
    Map<String,byte[]> objects=new ConcurrentHashMap<>();
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
        jdbc.update("DELETE FROM shelter.asset_submissions WHERE job_id IN (SELECT id FROM shelter.asset_jobs WHERE dog_id=?)",dog);
        jdbc.update("DELETE FROM shelter.styled_asset_steps WHERE job_id IN (SELECT id FROM shelter.asset_jobs WHERE dog_id=?)",dog);
        jdbc.update("DELETE FROM shelter.asset_jobs WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.asset_photo_sources WHERE photo_id=?",photo);
        jdbc.update("DELETE FROM shelter.asset_source_permissions WHERE shelter_id=?",shelter);
        jdbc.update("DELETE FROM shelter.behavior_suggestions WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.dog_behavior_evidence WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.dog_behavior_profiles WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.dog_observations WHERE dog_id=?",dog);
        jdbc.update("DELETE FROM shelter.dog_photos WHERE dog_id=?",dog);jdbc.update("DELETE FROM shelter.dogs WHERE id=?",dog);
        jdbc.update("DELETE FROM shelter.shelter_memberships WHERE shelter_id=?",shelter);jdbc.update("DELETE FROM shelter.shelters WHERE id=?",shelter);
        jdbc.update("DELETE FROM shelter.app_users WHERE id IN (?,?)",op,user);
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
        review(id,false,"APPROVE",200);publicStatus(200);
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
