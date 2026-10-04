package org.shelterconnect.api.asset;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.shelterconnect.api.auth.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Durable native-pixel state machine. Transactions never perform network or image work. */
@Service
public class StyledAssetStore {
    @io.swagger.v3.oas.annotations.media.Schema(name="StyledAssetStep")
    public record Step(String label,String action,String direction,String status,JsonNode result) {}
    @io.swagger.v3.oas.annotations.media.Schema(name="StyledAssetJob")
    public record Job(UUID id,UUID dogId,String status,String failureCode,String pipelineVersion,List<Step> steps,JsonNode seedReview) {}
    record Work(UUID id,UUID dogId,UUID token,String label,String action,String direction,String status,UUID providerId,
                Instant submittedAt,String bucket,String key,JsonNode traits,JsonNode providerResult) {
        boolean character() { return action.equals("BASE"); }
        String prefix() { return dogId+"/"+id+"/native-32/"; }
    }
    private final JdbcClient jdbc;private final JsonMapper json;private final ShelterAccessService access;
    private final AccountService accounts;private final AssetProperties properties;private final AssetStore legacy;
    public StyledAssetStore(JdbcClient jdbc,JsonMapper json,ShelterAccessService access,AccountService accounts,AssetProperties properties,AssetStore legacy) {
        this.jdbc=jdbc;this.json=json;this.access=access;this.accounts=accounts;this.properties=properties;this.legacy=legacy;
    }
    @Transactional public Job request(UUID subject,UUID dog,JsonNode body) {
        access.requireDogForWrite(subject,dog);properties.requireEnabled();AssetInput.fields(body,"photoId","traits");
        UUID photo=AssetInput.id(body,"photoId");JsonNode traits=StyledSpriteCodec.validateTraits(body.path("traits"));
        if(jdbc.sql("SELECT id FROM shelter.dog_photos WHERE id=:p AND dog_id=:d").param("p",photo).param("d",dog).query(UUID.class).optional().isEmpty()) throw missing();
        legacy.validPhoto(photo,null,true);
        // Canonical field ordering makes JSON object order irrelevant. Review prose does not buy another generation.
        var canonical=new TreeMap<String,Object>();traits.properties().forEach(e->{if(!e.getKey().equals("reviewNote"))canonical.put(e.getKey(),e.getValue());});
        String selection=StyledSpriteCodec.sha(json.writeValueAsBytes(canonical));
        var existing=jdbc.sql("SELECT id FROM shelter.asset_jobs WHERE photo_id=:p AND pipeline_version=:v AND selection_key=:s")
            .param("p",photo).param("v",StyledSpriteCodec.VERSION).param("s",selection).query(UUID.class).optional();
        if(existing.isPresent()) return job(existing.get());
        var plan=new ArrayList<String>();plan.add("BASE");plan.addAll(StyledSpriteCodec.ACTIONS);
        UUID id=jdbc.sql("""
            INSERT INTO shelter.asset_jobs(photo_id,dog_id,shelter_id,permission_id,pipeline_version,selection_key,action_plan,styled_input)
            SELECT p.id,p.dog_id,d.shelter_id,b.permission_id,:v,:s,CAST(:plan AS jsonb),CAST(:input AS jsonb)
            FROM shelter.dog_photos p JOIN shelter.dogs d ON d.id=p.dog_id JOIN shelter.asset_photo_sources b ON b.photo_id=p.id WHERE p.id=:p
            ON CONFLICT(photo_id,pipeline_version,selection_key) DO UPDATE SET photo_id=EXCLUDED.photo_id RETURNING id
            """).param("p",photo).param("v",StyledSpriteCodec.VERSION).param("s",selection).param("plan",json.writeValueAsString(plan))
            .param("input",json.writeValueAsString(traits)).query(UUID.class).single();
        insert(id,0,"character","BASE",null);int ordinal=1;
        for(String action:StyledSpriteCodec.ACTIONS) for(String direction:StyledSpriteCodec.DIRECTIONS)
            insert(id,ordinal++,action.toLowerCase(Locale.ROOT)+"-"+direction,action,direction);
        return job(id);
    }
    private void insert(UUID id,int ordinal,String label,String action,String direction) {
        jdbc.sql("INSERT INTO shelter.styled_asset_steps(job_id,ordinal,label,action,direction) VALUES (:id,:o,:l,:a,:d) ON CONFLICT DO NOTHING")
            .param("id",id).param("o",ordinal).param("l",label).param("a",action).param("d",direction,java.sql.Types.VARCHAR).update();
    }
    @Transactional(readOnly=true) public Job read(UUID subject,UUID dog,UUID id) {
        access.requireDog(subject,dog);var job=job(id);if(!job.dogId().equals(dog))throw missing();return job;
    }
    @Transactional(readOnly=true) public boolean isStyled(UUID id) {
        return jdbc.sql("SELECT count(*) FROM shelter.asset_jobs WHERE id=:id AND pipeline_version=:v").param("id",id).param("v",StyledSpriteCodec.VERSION).query(Integer.class).single()==1;
    }
    @Transactional(readOnly=true) public Job forManifest(UUID id) { legacy.valid(id,false);return job(id); }
    @Transactional(readOnly=true) public Job preview(UUID subject,UUID dog,UUID id) {
        var j=read(subject,dog,id);legacy.valid(id,false);
        if(!Set.of("SEED_REVIEW","REVIEW","APPROVED","REJECTED").contains(j.status()))throw new AssetException(409,"ASSET_NOT_READY");return j;
    }
    @Transactional public Job review(UUID subject,UUID dog,UUID id,JsonNode body,boolean seed) {
        var actor=access.requireDogForWrite(subject,dog);AssetInput.fields(body,"decision","note","expectedSeedHashes");
        String decision=AssetInput.text(body,"decision",16),note=AssetInput.text(body,"note",2000);
        if(!Set.of("APPROVE","REJECT").contains(decision) || note.length()<20)throw AssetException.invalid();
        lock(id);var j=job(id);if(!j.dogId().equals(dog))throw missing();legacy.valid(id,true);
        if(!j.status().equals(seed?"SEED_REVIEW":"REVIEW"))throw new AssetException(409,"ASSET_NOT_READY");
        var base=j.steps().getFirst().result();
        if(base==null || !base.path("hashes").equals(body.path("expectedSeedHashes")))throw new AssetException(409,"SEED_REVIEW_STALE");
        if(!seed && (j.steps().size()!=33 || j.steps().stream().anyMatch(s->!s.status().equals("SUCCEEDED")))) throw new AssetException(409,"ASSET_NOT_READY");
        if(seed && decision.equals("APPROVE")) {
            jdbc.sql("UPDATE shelter.asset_jobs SET seed_review=CAST(:r AS jsonb),status='QUEUED',next_run_at=now() WHERE id=:id")
                .param("r",json.writeValueAsString(Map.of("hashes",base.path("hashes"),"reviewedBy",actor.userId(),"note",note,"reviewedAt",Instant.now())))
                .param("id",id).update();
        } else jdbc.sql("UPDATE shelter.asset_jobs SET status=:s,reviewed_by=:u,reviewed_at=now() WHERE id=:id")
            .param("s",decision.equals("APPROVE")?"APPROVED":"REJECTED").param("u",actor.userId()).param("id",id).update();
        return job(id);
    }
    @Transactional public Job recover(UUID subject,UUID id,JsonNode body) {
        operator(subject);properties.requireEnabled();lock(id);legacy.valid(id,true);var j=job(id);
        AssetInput.fields(body,"providerJobId");
        if(j.status().equals("OUTCOME_UNKNOWN")) {
            UUID provider=AssetInput.id(body,"providerJobId");
            jdbc.sql("UPDATE shelter.styled_asset_steps SET status='WAITING',provider_job_id=:p,submitted_at=now() WHERE job_id=:id AND status='OUTCOME_UNKNOWN'")
                .param("p",provider).param("id",id).update();
        } else if(j.status().equals("FAILED") && !body.has("providerJobId")) {
            if("PROVIDER_JOB_FAILED".equals(j.failureCode())) jdbc.sql("""
                UPDATE shelter.styled_asset_steps SET attempt_history=attempt_history || jsonb_build_array(jsonb_build_object(
                  'providerJobId',provider_job_id,'submittedAt',submitted_at,'requestSha256',request_sha256,'status','FAILED')),
                  provider_job_id=NULL,submitted_at=NULL
                WHERE job_id=:id AND status='FAILED' AND provider_result IS NULL
                """).param("id",id).update();
            jdbc.sql("""
                UPDATE shelter.styled_asset_steps SET status=CASE WHEN provider_result IS NOT NULL THEN 'PERSISTING'
                  WHEN provider_job_id IS NOT NULL THEN 'WAITING' ELSE 'PENDING' END
                WHERE job_id=:id AND status='FAILED'
                """).param("id",id).update();
        } else throw new AssetException(409,"ASSET_RETRY_NOT_ALLOWED");
        jdbc.sql("UPDATE shelter.asset_jobs SET status='QUEUED',failure_code=NULL,next_run_at=now(),lease_token=NULL,lease_until=NULL WHERE id=:id").param("id",id).update();
        return job(id);
    }
    @Transactional public Work claim() {
        var id=jdbc.sql("""
            SELECT id FROM shelter.asset_jobs WHERE pipeline_version=:v AND status IN ('QUEUED','RUNNING')
              AND next_run_at<=now() AND (lease_until IS NULL OR lease_until<now()) ORDER BY next_run_at,id FOR UPDATE SKIP LOCKED LIMIT 1
            """).param("v",StyledSpriteCodec.VERSION).query(UUID.class).optional();
        if(id.isEmpty())return null;
        if(!validOrCancel(id.get()))return null;
        UUID token=UUID.randomUUID();
        jdbc.sql("UPDATE shelter.asset_jobs SET status='RUNNING',lease_token=:t,lease_until=now()+interval '5 minutes' WHERE id=:id").param("t",token).param("id",id.get()).update();
        var w=jdbc.sql("""
            SELECT j.id,j.dog_id,j.styled_input::text,s.*,b.storage_bucket,b.storage_key
            FROM shelter.asset_jobs j JOIN shelter.styled_asset_steps s ON s.job_id=j.id JOIN shelter.asset_photo_sources b ON b.photo_id=j.photo_id
            WHERE j.id=:id AND s.status<>'SUCCEEDED' ORDER BY s.ordinal LIMIT 1
            """).param("id",id.get()).query((r,n)->new Work(id.get(),r.getObject("dog_id",UUID.class),token,r.getString("label"),r.getString("action"),r.getString("direction"),r.getString("status"),r.getObject("provider_job_id",UUID.class),
                r.getTimestamp("submitted_at")==null?null:r.getTimestamp("submitted_at").toInstant(),r.getString("storage_bucket"),r.getString("storage_key"),json.readTree(r.getString("styled_input")),r.getString("provider_result")==null?null:json.readTree(r.getString("provider_result")))).optional();
        if(w.isEmpty()) { status(id.get(),"REVIEW",null);return null; }
        if(w.get().status().equals("SUBMITTING")) { fail(w.get(),true,"SUBMISSION_INTERRUPTED");return null; }
        if(!w.get().character()) {
            var j=job(id.get());
            if(j.seedReview()==null || !j.seedReview().path("hashes").equals(j.steps().getFirst().result().path("hashes"))) {
                status(id.get(),"SEED_REVIEW","SEED_REVIEW_REQUIRED");return null;
            }
        }
        return w.get();
    }
    @Transactional public boolean authorized(Work w) { return owned(w) && validOrCancel(w.id()); }
    @Transactional public boolean reserve(Work w,JsonNode payload) {
        if(!authorized(w))return false;
        jdbc.sql("SELECT pg_advisory_xact_lock(15150923)").query(Object.class).single();
        long used=jdbc.sql("SELECT count(*) FROM shelter.asset_submissions WHERE submitted_at>=date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'").query(Long.class).single();
        if(used>=properties.dailyRequests) {
            jdbc.sql("UPDATE shelter.asset_jobs SET lease_token=NULL,lease_until=NULL,failure_code='DAILY_REQUEST_LIMIT',next_run_at=(date_trunc('day',now() AT TIME ZONE 'UTC')+interval '1 day') AT TIME ZONE 'UTC' WHERE id=:id").param("id",w.id()).update();return false;
        }
        if(jdbc.sql("UPDATE shelter.styled_asset_steps SET status='SUBMITTING',submitted_at=now(),request_sha256=:h WHERE job_id=:id AND label=:l AND status='PENDING'")
            .param("h",StyledSpriteCodec.sha(json.writeValueAsBytes(payload))).param("id",w.id()).param("l",w.label()).update()!=1)return false;
        jdbc.sql("INSERT INTO shelter.asset_submissions(job_id,action) VALUES (:id,:a)").param("id",w.id()).param("a",w.action()).update();return true;
    }
    @Transactional public void accepted(Work w,UUID provider) {
        // Record an acknowledgement even if permissions were revoked during the remote POST.
        // Never revive a cancelled/reviewed job, and never discard a known paid job ID.
        jdbc.sql("UPDATE shelter.styled_asset_steps SET provider_job_id=:p,status='WAITING' WHERE job_id=:id AND label=:l AND status IN ('SUBMITTING','OUTCOME_UNKNOWN') AND provider_job_id IS NULL")
            .param("p",provider).param("id",w.id()).param("l",w.label()).update();
        if(owned(w))defer(w,5);
    }
    @Transactional public void checkpoint(Work w,JsonNode result) {
        if(!authorized(w))return;
        jdbc.sql("UPDATE shelter.styled_asset_steps SET provider_result=CAST(:r AS jsonb),status='PERSISTING' WHERE job_id=:id AND label=:l AND status='WAITING'")
            .param("r",json.writeValueAsString(result)).param("id",w.id()).param("l",w.label()).update();
    }
    @Transactional public JsonNode seed(Work w) {
        if(!authorized(w))throw new AssetException(409,"ASSET_LEASE_LOST");
        return job(w.id()).steps().getFirst().result();
    }
    @Transactional public void success(Work w,JsonNode result) {
        if(!authorized(w))return;
        jdbc.sql("UPDATE shelter.styled_asset_steps SET result=CAST(:r AS jsonb),provider_result=NULL,status='SUCCEEDED' WHERE job_id=:id AND label=:l AND status='PERSISTING'")
            .param("r",json.writeValueAsString(result)).param("id",w.id()).param("l",w.label()).update();
        if(w.character())status(w.id(),"SEED_REVIEW",null);
        else if(job(w.id()).steps().stream().allMatch(s->s.status().equals("SUCCEEDED")))status(w.id(),"REVIEW",null);
        else defer(w,0);
    }
    @Transactional public void defer(Work w,int seconds) {
        if(!owned(w))return;
        jdbc.sql("UPDATE shelter.asset_jobs SET lease_token=NULL,lease_until=NULL,next_run_at=now()+(:s * interval '1 second'),failure_code=NULL WHERE id=:id")
            .param("s",seconds).param("id",w.id()).update();
    }
    @Transactional public void fail(Work w,boolean unknown,String code) {
        if(!owned(w))return;
        String state=unknown?"OUTCOME_UNKNOWN":"FAILED";
        jdbc.sql("UPDATE shelter.styled_asset_steps SET status=:s WHERE job_id=:id AND label=:l").param("s",state).param("id",w.id()).param("l",w.label()).update();
        status(w.id(),state,code);
    }
    private void status(UUID id,String status,String code) {
        jdbc.sql("UPDATE shelter.asset_jobs SET status=:s,failure_code=:c,lease_token=NULL,lease_until=NULL WHERE id=:id")
            .param("s",status).param("c",code,java.sql.Types.VARCHAR).param("id",id).update();
    }
    private boolean validOrCancel(UUID id) {
        try { legacy.valid(id,true);return true; }
        catch(AssetException e) { status(id,"CANCELLED","SOURCE_NO_LONGER_ALLOWED");return false; }
    }
    private boolean owned(Work w) { return jdbc.sql("SELECT id FROM shelter.asset_jobs WHERE id=:id AND lease_token=:t AND lease_until>now() AND status='RUNNING' FOR UPDATE")
        .param("id",w.id()).param("t",w.token()).query(UUID.class).optional().isPresent(); }
    private void lock(UUID id) { if(jdbc.sql("SELECT id FROM shelter.asset_jobs WHERE id=:id AND pipeline_version=:v FOR UPDATE").param("id",id).param("v",StyledSpriteCodec.VERSION).query(UUID.class).optional().isEmpty())throw missing(); }
    private void operator(UUID subject) { if(!accounts.lockProfile(subject,false).role().equals("OPERATOR"))throw new AssetException(403,"FORBIDDEN"); }
    private Job job(UUID id) {
        var steps=jdbc.sql("SELECT label,action,direction,status,result::text FROM shelter.styled_asset_steps WHERE job_id=:id ORDER BY ordinal").param("id",id)
            .query((r,n)->new Step(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5)==null?null:json.readTree(r.getString(5)))).list();
        return jdbc.sql("SELECT dog_id,status,failure_code,seed_review::text FROM shelter.asset_jobs WHERE id=:id AND pipeline_version=:v")
            .param("id",id).param("v",StyledSpriteCodec.VERSION).query((r,n)->new Job(id,r.getObject(1,UUID.class),r.getString(2),r.getString(3),StyledSpriteCodec.VERSION,steps,r.getString(4)==null?null:json.readTree(r.getString(4)))).optional().orElseThrow(StyledAssetStore::missing);
    }
    private static AssetException missing() { return new AssetException(404,"ASSET_NOT_FOUND"); }
}
