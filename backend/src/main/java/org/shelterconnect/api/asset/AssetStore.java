package org.shelterconnect.api.asset;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.shelterconnect.api.auth.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Service
public class AssetStore {
    public record Job(UUID id,UUID dogId,String status,String failureCode,Instant createdAt,List<Step> steps,
        List<String> actionPlan,Integer behaviorRevision,int rigRevision,JsonNode rigProfile,boolean rigConfirmed) {}
    public record Step(String action,String status,JsonNode result) {}
    record Work(UUID id,UUID dogId,UUID photoId,UUID token,AssetAction action,String stepStatus,UUID providerId,
                String photoBucket,String photoKey,Instant submittedAt) {
        String prefix() { return dogId+"/"+id+"/"; }
    }
    private final JdbcClient jdbc;
    private final JsonMapper json;
    private final ShelterAccessService access;
    private final AccountService accounts;
    private final AssetProperties properties;
    private final org.shelterconnect.api.behavior.BehaviorService behaviors;
    public AssetStore(JdbcClient jdbc,JsonMapper json,ShelterAccessService access,AccountService accounts,AssetProperties properties,org.shelterconnect.api.behavior.BehaviorService behaviors) {
        this.jdbc=jdbc;this.json=json;this.access=access;this.accounts=accounts;this.properties=properties;this.behaviors=behaviors;
    }
    @Transactional
    public UUID permission(UUID subject,JsonNode body) {
        UUID user=operator(subject);
        AssetInput.fields(body,"shelterId","sourceKey","sourceKind","permissionNote","crawlAllowed","derivativesAllowed","pixellabAllowed","autoGenerate");
        UUID shelter=AssetInput.id(body,"shelterId");
        String key=AssetInput.text(body,"sourceKey",160), kind=AssetInput.text(body,"sourceKind",16), note=AssetInput.text(body,"permissionNote",2000);
        boolean crawl=AssetInput.bool(body,"crawlAllowed"), derivatives=AssetInput.bool(body,"derivativesAllowed"),
            pixellab=AssetInput.bool(body,"pixellabAllowed"), auto=AssetInput.bool(body,"autoGenerate");
        if(!Set.of("SHELTER","CRAWL").contains(kind) || (auto&&(!derivatives||!pixellab||(kind.equals("CRAWL")&&!crawl)))) throw AssetException.invalid();
        if(jdbc.sql("SELECT id FROM shelter.shelters WHERE id=:s AND approval_status='APPROVED' FOR SHARE")
            .param("s",shelter).query(UUID.class).optional().isEmpty()) throw new AssetException(409,"SHELTER_NOT_APPROVED");
        return jdbc.sql("""
            INSERT INTO shelter.asset_source_permissions(shelter_id,source_key,source_kind,permission_note,
                crawl_allowed,derivatives_allowed,pixellab_allowed,auto_generate,recorded_by)
            VALUES (:s,:k,:kind,:note,:crawl,:derivatives,:pixellab,:auto,:user) RETURNING id
            """).param("s",shelter).param("k",key).param("kind",kind).param("note",note).param("crawl",crawl)
            .param("derivatives",derivatives).param("pixellab",pixellab).param("auto",auto).param("user",user).query(UUID.class).single();
    }
    @Transactional
    public void revoke(UUID subject,UUID permission) {
        operator(subject);
        if(jdbc.sql("UPDATE shelter.asset_source_permissions SET revoked_at=coalesce(revoked_at,now()) WHERE id=:id")
            .param("id",permission).update()==0) throw missing();
        jdbc.sql("UPDATE shelter.asset_jobs SET status='CANCELLED',failure_code='PERMISSION_REVOKED',lease_token=NULL,lease_until=NULL WHERE permission_id=:p")
            .param("p",permission).update();
    }
    @Transactional
    public Map<String,Object> imported(UUID subject,JsonNode body) {
        operator(subject); AssetInput.fields(body,"photoId","permissionId");
        return photoStored(AssetInput.id(body,"photoId"),AssetInput.id(body,"permissionId"));
    }
    /** Trusted importer hook. Call after storing a rights-cleared photo at an immutable private Storage key. */
    @Transactional
    public Map<String,Object> photoStored(UUID photo,UUID permission) {
        var id=jdbc.sql("""
            SELECT p.id FROM shelter.dog_photos p JOIN shelter.dogs d ON d.id=p.dog_id
            JOIN shelter.shelters s ON s.id=d.shelter_id
            JOIN shelter.asset_source_permissions a ON a.shelter_id=s.id
            WHERE p.id=:photo AND a.id=:permission AND p.rights_status='GRANTED' AND d.archived_at IS NULL
              AND s.approval_status='APPROVED' AND a.revoked_at IS NULL AND a.derivatives_allowed AND a.pixellab_allowed
              AND (a.source_kind<>'CRAWL' OR a.crawl_allowed) FOR SHARE OF p,d,s,a
            """).param("photo",photo).param("permission",permission).query(UUID.class).optional();
        if(id.isEmpty()) throw new AssetException(409,"ASSET_PERMISSION_REQUIRED");
        jdbc.sql("""
            INSERT INTO shelter.asset_photo_sources(photo_id,permission_id,storage_bucket,storage_key)
            SELECT id,:permission,storage_bucket,storage_key FROM shelter.dog_photos WHERE id=:photo
            ON CONFLICT (photo_id) DO NOTHING
            """).param("photo",photo).param("permission",permission).update();
        UUID existing=jdbc.sql("SELECT permission_id FROM shelter.asset_photo_sources WHERE photo_id=:p").param("p",photo).query(UUID.class).single();
        if(!existing.equals(permission)) throw new AssetException(409,"PHOTO_SOURCE_ALREADY_BOUND");
        boolean auto=jdbc.sql("SELECT auto_generate FROM shelter.asset_source_permissions WHERE id=:p").param("p",permission).query(Boolean.class).single();
        if(auto && properties.enabled && properties.autoImport) return Map.of("photoId",photo,"job",enqueue(photo));
        return Map.of("photoId",photo,"status","REGISTERED","automaticGenerationEnabled",false);
    }
    @Transactional
    public Job request(UUID subject,UUID dog,JsonNode body) {
        access.requireDogForWrite(subject,dog); properties.requireEnabled();AssetInput.fields(body,"photoId");
        UUID photo=AssetInput.id(body,"photoId");
        if(jdbc.sql("SELECT id FROM shelter.dog_photos WHERE id=:p AND dog_id=:d").param("p",photo).param("d",dog).query(UUID.class).optional().isEmpty()) throw missing();
        return enqueue(photo);
    }
    private Job enqueue(UUID photo) {
        validPhoto(photo,null,true);
        UUID dog=jdbc.sql("SELECT dog_id FROM shelter.dog_photos WHERE id=:p").param("p",photo).query(UUID.class).single();
        var plan=behaviors.assetSelection(dog);
        String selectionKey=String.valueOf(plan.revision())+":"+String.join(",",plan.actions());
        UUID id=jdbc.sql("""
            INSERT INTO shelter.asset_jobs(photo_id,dog_id,shelter_id,permission_id,pipeline_version,action_plan,behavior_revision,selection_key)
            SELECT p.id,p.dog_id,d.shelter_id,a.permission_id,:version,CAST(:plan AS jsonb),:revision,:selection FROM shelter.dog_photos p
              JOIN shelter.dogs d ON d.id=p.dog_id JOIN shelter.asset_photo_sources a ON a.photo_id=p.id WHERE p.id=:photo
            ON CONFLICT (photo_id,pipeline_version,selection_key) DO UPDATE SET photo_id=EXCLUDED.photo_id RETURNING id
            """).param("version",AssetAction.VERSION).param("photo",photo).param("plan",json.writeValueAsString(plan.actions()))
            .param("revision",plan.revision()).param("selection",selectionKey).query(UUID.class).single();
        for(AssetAction action:plan.actions().stream().map(AssetAction::valueOf).toList()) jdbc.sql("""
            INSERT INTO shelter.asset_steps(job_id,ordinal,action) VALUES (:id,:ordinal,:action) ON CONFLICT DO NOTHING
            """).param("id",id).param("ordinal",action.ordinal()).param("action",action.name()).update();
        return job(id);
    }
    @Transactional(readOnly=true)
    public Job read(UUID subject,UUID dog,UUID id) {
        access.requireDog(subject,dog); Job job=job(id);if(!job.dogId().equals(dog)) throw missing();return job;
    }
    @Transactional
    public Job review(UUID subject,UUID dog,UUID id,JsonNode body) {
        var writer=access.requireDogForWrite(subject,dog);AssetInput.fields(body,"decision");
        String decision=AssetInput.text(body,"decision",16);
        if(!Set.of("APPROVE","REJECT").contains(decision)) throw AssetException.invalid();
        lock(id);Job job=job(id);if(!job.dogId().equals(dog)) throw missing();
        if(!job.status().equals("REVIEW") || !stepsComplete(job) || job.steps().stream().anyMatch(s->!s.status().equals("SUCCEEDED"))) throw new AssetException(409,"ASSET_NOT_READY");
        valid(id,true);
        jdbc.sql("UPDATE shelter.asset_jobs SET status=:status,reviewed_by=:u,reviewed_at=now() WHERE id=:id")
            .param("status",decision.equals("APPROVE")?"APPROVED":"REJECTED").param("u",writer.userId()).param("id",id).update();
        return job(id);
    }
    @Transactional
    public Job retry(UUID subject,UUID id) {
        operator(subject);properties.requireEnabled();lock(id);valid(id,true);
        if(!job(id).status().equals("FAILED")) throw new AssetException(409,"ASSET_RETRY_NOT_ALLOWED");
        jdbc.sql("UPDATE shelter.asset_steps SET status='PENDING',provider_job_id=NULL WHERE job_id=:id AND status='FAILED'").param("id",id).update();
        // Submitted rows retain their timestamp for the daily quota; retries are counted in a separate ledger (see reserve).
        jdbc.sql("UPDATE shelter.asset_jobs SET status='QUEUED',failure_code=NULL,next_run_at=now(),lease_token=NULL,lease_until=NULL WHERE id=:id").param("id",id).update();
        return job(id);
    }
    @Transactional
    public Job reconcile(UUID subject,UUID id,JsonNode body) {
        operator(subject);properties.requireEnabled();AssetInput.fields(body,"providerJobId");
        UUID provider=AssetInput.id(body,"providerJobId");lock(id);valid(id,true);
        if(!job(id).status().equals("OUTCOME_UNKNOWN")) throw new AssetException(409,"ASSET_RECONCILE_NOT_ALLOWED");
        jdbc.sql("UPDATE shelter.asset_steps SET status='WAITING',provider_job_id=:provider,submitted_at=now() WHERE job_id=:id AND status='OUTCOME_UNKNOWN'")
            .param("provider",provider).param("id",id).update();
        jdbc.sql("UPDATE shelter.asset_jobs SET status='RUNNING',failure_code=NULL,next_run_at=now(),lease_token=NULL,lease_until=NULL WHERE id=:id").param("id",id).update();
        return job(id);
    }
    @Transactional(readOnly=true)
    public Job available(UUID dog) {
        UUID id=jdbc.sql("""
            SELECT j.id FROM shelter.asset_jobs j JOIN shelter.dogs d ON d.id=j.dog_id JOIN shelter.shelters s ON s.id=d.shelter_id
            WHERE d.id=:dog AND j.status='APPROVED' AND d.is_public AND s.is_public AND s.approval_status='APPROVED'
                AND d.archived_at IS NULL AND d.adoption_status IN ('AVAILABLE','IN_PROGRESS') ORDER BY j.reviewed_at DESC LIMIT 1
            """).param("dog",dog).query(UUID.class).optional().orElseThrow(AssetStore::missing);
        valid(id,false);return job(id);
    }
    @Transactional(readOnly=true)
    public void checkPreview(UUID subject,UUID dog,UUID id) {
        var job=read(subject,dog,id);
        if(!Set.of("REVIEW","APPROVED","REJECTED").contains(job.status())) throw new AssetException(409,"ASSET_NOT_READY");
        valid(id,false);
    }
    @Transactional
    public Work claim() {
        var id=jdbc.sql("""
            SELECT id FROM shelter.asset_jobs WHERE status IN ('QUEUED','RUNNING') AND next_run_at<=now()
              AND pipeline_version=:version AND (lease_until IS NULL OR lease_until<now()) ORDER BY next_run_at,id FOR UPDATE SKIP LOCKED LIMIT 1
            """).param("version",AssetAction.VERSION).query(UUID.class).optional();
        if(id.isEmpty()) return null;
        try { valid(id.get(),true); } catch(AssetException e) { cancel(id.get());return null; }
        UUID token=UUID.randomUUID();
        jdbc.sql("UPDATE shelter.asset_jobs SET status='RUNNING',lease_token=:t,lease_until=now()+interval '3 minutes' WHERE id=:id")
            .param("t",token).param("id",id.get()).update();
        var work=jdbc.sql("""
            SELECT j.id,j.dog_id,j.photo_id,j.lease_token,t.action,t.status,t.provider_job_id,t.submitted_at,p.storage_bucket,p.storage_key
            FROM shelter.asset_jobs j JOIN shelter.asset_steps t ON t.job_id=j.id JOIN shelter.asset_photo_sources p ON p.photo_id=j.photo_id
            WHERE j.id=:id AND t.status<>'SUCCEEDED' ORDER BY t.ordinal LIMIT 1
            """).param("id",id.get()).query((rs,n)->work(rs)).optional();
        if(work.isEmpty()) { complete(id.get());return null; }
        if(work.get().stepStatus().equals("SUBMITTING")) { fail(work.get(),"OUTCOME_UNKNOWN","SUBMISSION_INTERRUPTED");return null; }
        return work.get();
    }
    @Transactional
    public boolean reserve(Work work) {
        if(!owned(work)) return false;
        try { valid(work.id(),true); } catch(AssetException e) { cancel(work.id());return false; }
        jdbc.sql("SELECT pg_advisory_xact_lock(15150923)").query(Object.class).single();
        long used=jdbc.sql("SELECT count(*) FROM shelter.asset_submissions WHERE submitted_at >= date_trunc('day',now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'").query(Long.class).single();
        if(used>=properties.dailyRequests) {
            jdbc.sql("UPDATE shelter.asset_jobs SET lease_token=NULL,lease_until=NULL,failure_code='DAILY_REQUEST_LIMIT',next_run_at=(date_trunc('day',now() AT TIME ZONE 'UTC')+interval '1 day') AT TIME ZONE 'UTC' WHERE id=:id").param("id",work.id()).update();return false;
        }
        jdbc.sql("INSERT INTO shelter.asset_submissions(job_id,action) VALUES (:id,:action)").param("id",work.id()).param("action",work.action().name()).update();
        jdbc.sql("UPDATE shelter.asset_steps SET status='SUBMITTING',submitted_at=now() WHERE job_id=:id AND action=:a AND status='PENDING'")
            .param("id",work.id()).param("a",work.action().name()).update();
        return true;
    }
    @Transactional
    public void accepted(Work work,UUID provider) {
        if(!owned(work)) return;
        jdbc.sql("UPDATE shelter.asset_steps SET status='WAITING',provider_job_id=:p WHERE job_id=:id AND action=:a AND status='SUBMITTING'")
            .param("p",provider).param("id",work.id()).param("a",work.action().name()).update();
        release(work,15);
    }
    @Transactional
    public boolean authorized(Work work) {
        if(!owned(work)) return false;
        try { valid(work.id(),true);return true; } catch(AssetException e) { cancel(work.id());return false; }
    }
    @Transactional
    public void success(Work work,Map<String,Object> result) {
        if(!authorized(work)) return;
        jdbc.sql("UPDATE shelter.asset_steps SET status='SUCCEEDED',result=CAST(:r AS jsonb) WHERE job_id=:id AND action=:a AND status IN ('WAITING','RENDERING')")
            .param("r",json.writeValueAsString(result)).param("id",work.id()).param("a",work.action().name()).update();
        long left=jdbc.sql("SELECT count(*) FROM shelter.asset_steps WHERE job_id=:id AND status<>'SUCCEEDED'").param("id",work.id()).query(Long.class).single();
        if(left==0) complete(work.id());else release(work,1);
    }
    @Transactional
    public void release(Work work,int seconds) {
        jdbc.sql("UPDATE shelter.asset_jobs SET lease_token=NULL,lease_until=NULL,next_run_at=now()+(:s * interval '1 second') WHERE id=:id AND lease_token=:t")
            .param("id",work.id()).param("t",work.token()).param("s",seconds).update();
    }
    @Transactional
    public void fail(Work work,String status,String code) {
        if(!owned(work)) return;
        jdbc.sql("UPDATE shelter.asset_steps SET status=:status WHERE job_id=:id AND action=:a AND status<>'SUCCEEDED'")
            .param("status",status).param("id",work.id()).param("a",work.action().name()).update();
        jdbc.sql("UPDATE shelter.asset_jobs SET status=:status,failure_code=:c,lease_token=NULL,lease_until=NULL WHERE id=:id")
            .param("status",status).param("c",code).param("id",work.id()).update();
    }
    static boolean stepsComplete(Job job) {
        return job.steps().size()==job.actionPlan().size() && job.actionPlan().contains("BASE")
            && job.actionPlan().contains("IDLE") && job.actionPlan().contains("WALK")
            && new HashSet<>(job.actionPlan()).equals(job.steps().stream().map(Step::action).collect(java.util.stream.Collectors.toSet()));
    }
    @Transactional
    public void baseReady(Work work,Map<String,Object> result,JsonNode proposed,String sha256) {
        if(!authorized(work)) return;
        if(jdbc.sql("UPDATE shelter.asset_steps SET status='SUCCEEDED',result=CAST(:r AS jsonb) WHERE job_id=:id AND action='BASE' AND status='WAITING'")
            .param("r",json.writeValueAsString(result)).param("id",work.id()).update()!=1) return;
        jdbc.sql("UPDATE shelter.asset_jobs SET status='RIG_REVIEW',rig_profile=CAST(:p AS jsonb),rig_revision=rig_revision+1,rig_base_sha256=:hash,lease_token=NULL,lease_until=NULL,failure_code=:failure WHERE id=:id")
            .param("id",work.id()).param("p",proposed==null?null:json.writeValueAsString(proposed)).param("hash",sha256)
            .param("failure",proposed==null?"RIG_PROFILE_REQUIRED":null).update();
    }
    @Transactional(readOnly=true)
    public Job rigForReview(UUID subject,UUID dog,UUID id) {
        var result=read(subject,dog,id);valid(id,false);
        if(!result.status().equals("RIG_REVIEW")) throw new AssetException(409,"RIG_NOT_REVIEWABLE");
        return result;
    }
    @Transactional
    public Job rigForConfirmation(UUID subject,UUID dog,UUID id) {
        access.requireDogForWrite(subject,dog);valid(id,true);return rigForReview(subject,dog,id);
    }
    @Transactional
    public Job confirmRig(UUID subject,UUID dog,UUID id,int expected,JsonNode profile,String baseHash) {
        var actor=access.requireDogForWrite(subject,dog);lock(id);
        var current=rigForReview(subject,dog,id);valid(id,true);
        if(current.rigRevision()!=expected) throw new AssetException(409,"RIG_CHANGED");
        String actual=jdbc.sql("SELECT rig_base_sha256 FROM shelter.asset_jobs WHERE id=:id").param("id",id).query(String.class).single();
        if(!Objects.equals(actual,baseHash)) throw new AssetException(409,"RIG_BASE_CHANGED");
        jdbc.sql("UPDATE shelter.asset_jobs SET rig_profile=CAST(:p AS jsonb),rig_revision=rig_revision+1,rig_confirmed_by=:u,rig_confirmed_at=now(),status='QUEUED',failure_code=NULL,next_run_at=now() WHERE id=:id")
            .param("id",id).param("p",json.writeValueAsString(profile)).param("u",actor.userId()).update();
        return job(id);
    }
    @Transactional
    public JsonNode localProfile(Work work) {
        if(!authorized(work)) return null;
        var current=job(work.id());
        if(!current.rigConfirmed() || current.rigProfile()==null) throw new AssetException(409,"RIG_CONFIRMATION_REQUIRED");
        return current.rigProfile();
    }
    @Transactional
    public boolean reserveLocal(Work work) {
        if(!authorized(work)) return false;
        return jdbc.sql("UPDATE shelter.asset_steps SET status='RENDERING' WHERE job_id=:id AND action=:action AND status IN ('PENDING','RENDERING')")
            .param("id",work.id()).param("action",work.action().name()).update()==1;
    }
    @Transactional
    public void rigNeedsReview(Work work) {
        if(!authorized(work)) return;
        jdbc.sql("UPDATE shelter.asset_steps SET status='PENDING',result=NULL WHERE job_id=:id AND action IN ('WALK','RUN','BACK_OFF')")
            .param("id",work.id()).update();
        jdbc.sql("UPDATE shelter.asset_jobs SET status='RIG_REVIEW',rig_revision=rig_revision+1,rig_confirmed_by=NULL,rig_confirmed_at=NULL,lease_token=NULL,lease_until=NULL,failure_code='RIG_PROFILE_REQUIRES_REVIEW' WHERE id=:id")
            .param("id",work.id()).update();
    }
    private UUID operator(UUID subject) {
        var user=accounts.lockProfile(subject,false);
        if(!user.role().equals("OPERATOR")) throw new AssetException(403,"FORBIDDEN");
        return user.id();
    }
    private Job job(UUID id) {
        var steps=jdbc.sql("SELECT action,status,result::text AS result FROM shelter.asset_steps WHERE job_id=:id ORDER BY ordinal")
            .param("id",id).query((rs,n)->new Step(rs.getString("action"),rs.getString("status"),rs.getString("result")==null?null:json.readTree(rs.getString("result")))).list();
        return jdbc.sql("SELECT id,dog_id,status,failure_code,created_at,action_plan::text,behavior_revision,rig_revision,rig_profile::text,rig_confirmed_at FROM shelter.asset_jobs WHERE id=:id").param("id",id)
            .query((rs,n)->new Job(id,rs.getObject("dog_id",UUID.class),rs.getString("status"),rs.getString("failure_code"),rs.getTimestamp("created_at").toInstant(),steps,
                json.readTree(rs.getString("action_plan")).valueStream().map(JsonNode::asText).toList(),
                rs.getObject("behavior_revision",Integer.class),rs.getInt("rig_revision"),
                rs.getString("rig_profile")==null?null:json.readTree(rs.getString("rig_profile")),rs.getTimestamp("rig_confirmed_at")!=null)).optional().orElseThrow(AssetStore::missing);
    }
    private Work work(ResultSet r) throws SQLException {
        var at=r.getTimestamp("submitted_at");
        return new Work(r.getObject("id",UUID.class),r.getObject("dog_id",UUID.class),r.getObject("photo_id",UUID.class),r.getObject("lease_token",UUID.class),
            AssetAction.valueOf(r.getString("action")),r.getString("status"),r.getObject("provider_job_id",UUID.class),r.getString("storage_bucket"),r.getString("storage_key"),at==null?null:at.toInstant());
    }
    private void lock(UUID id) { if(jdbc.sql("SELECT id FROM shelter.asset_jobs WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional().isEmpty()) throw missing(); }
    private boolean owned(Work work) {
        return jdbc.sql("SELECT id FROM shelter.asset_jobs WHERE id=:id AND lease_token=:t AND lease_until>now() AND status='RUNNING' FOR UPDATE")
            .param("id",work.id()).param("t",work.token()).query(UUID.class).optional().isPresent();
    }
    private void valid(UUID id,boolean lock) {
        var ids=jdbc.sql("SELECT j.photo_id,j.permission_id FROM shelter.asset_jobs j JOIN shelter.dog_photos p ON p.id=j.photo_id JOIN shelter.dogs d ON d.id=p.dog_id WHERE j.id=:id AND j.dog_id=p.dog_id AND j.shelter_id=d.shelter_id").param("id",id)
            .query((r,n)->new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class)}).optional().orElseThrow(AssetStore::missing);
        validPhoto(ids[0],ids[1],lock);
    }
    private void validPhoto(UUID photo,UUID permission,boolean lock) {
        boolean valid=jdbc.sql("""
            SELECT p.id FROM shelter.dog_photos p JOIN shelter.asset_photo_sources b ON b.photo_id=p.id
            JOIN shelter.asset_source_permissions a ON a.id=b.permission_id JOIN shelter.dogs d ON d.id=p.dog_id
            JOIN shelter.shelters s ON s.id=d.shelter_id
            WHERE p.id=:p AND p.rights_status='GRANTED' AND p.storage_bucket=b.storage_bucket AND p.storage_key=b.storage_key
              AND d.shelter_id=a.shelter_id AND d.archived_at IS NULL AND s.approval_status='APPROVED'
              AND a.revoked_at IS NULL AND a.derivatives_allowed AND a.pixellab_allowed
              AND (a.source_kind<>'CRAWL' OR a.crawl_allowed)
            """+(permission==null?"":" AND a.id=:permission")+(lock?" FOR SHARE OF p,b,a,d,s":""))
            .params(permission==null?Map.of("p",photo):Map.of("p",photo,"permission",permission)).query(UUID.class).optional().isPresent();
        if(!valid) throw new AssetException(409,"ASSET_PERMISSION_REQUIRED");
    }
    private void cancel(UUID id) { jdbc.sql("UPDATE shelter.asset_jobs SET status='CANCELLED',failure_code='SOURCE_NO_LONGER_ALLOWED',lease_token=NULL,lease_until=NULL WHERE id=:id").param("id",id).update(); }
    private void complete(UUID id) { jdbc.sql("UPDATE shelter.asset_jobs SET status='REVIEW',failure_code=NULL,lease_token=NULL,lease_until=NULL WHERE id=:id").param("id",id).update(); }
    private static AssetException missing() { return new AssetException(404,"ASSET_NOT_FOUND"); }
}
