package org.shelterconnect.api.asset;

import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.shelterconnect.api.auth.ShelterAccessService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Durable learning handoff. No image, provider or model calls in a transaction. */
@Service
public class StyledLearningRecoveryStore {
    static final String VERSION="validated-motion-learning-v2";
    static final String LEGACY_VERSION="validated-idle-learning-v1";
    static final Set<String> ACTIONS=Set.of("IDLE","WALK","SIT");
    record Work(UUID job,UUID dog,String label,UUID token,String action,String direction,JsonNode policy,JsonNode seeds,
                JsonNode result,JsonNode report,JsonNode referenceReport) {
        String prefix(){return dog+"/"+job+"/native-32/";}
    }
    record Reference(UUID id,UUID job,UUID dog,String label,JsonNode result,JsonNode seeds) {
        String prefix(){return dog+"/"+job+"/native-32/";}
    }
    private final JdbcClient jdbc;private final JsonMapper json;private final AssetStore assets;
    private final StyledAssetStore styled;private final ShelterAccessService access;private final AssetProperties properties;
    public StyledLearningRecoveryStore(JdbcClient jdbc,JsonMapper json,AssetStore assets,StyledAssetStore styled,ShelterAccessService access,AssetProperties properties) {
        this.jdbc=jdbc;this.json=json;this.assets=assets;this.styled=styled;this.access=access;this.properties=properties;
    }
    @Transactional public StyledAssetStore.Job arm(UUID subject,UUID dog,UUID id,JsonNode body) {
        access.requireDogForWrite(subject,dog);properties.requireEnabled();
        AssetInput.fields(body,"requestId","note","expectedSeedHashes","expectedSheetHashes");
        UUID requestId=AssetInput.id(body,"requestId");if(AssetInput.text(body,"note",2000).length()<20)throw AssetException.invalid();
        var expected=body.path("expectedSheetHashes");
        if(!expected.isObject() || expected.isEmpty() || expected.size()>12)throw AssetException.invalid();
        // Canonicalize hashes so equivalent JSON ordering does not change consent.
        var canonical=new TreeMap<String,Object>();canonical.put("requestId",body.path("requestId").asText());canonical.put("note",body.path("note").asText());
        for(String field:List.of("expectedSeedHashes","expectedSheetHashes")) {
            var values=new TreeMap<String,String>();body.path(field).properties().forEach(e->values.put(e.getKey(),e.getValue().asText()));canonical.put(field,values);
        }
        String hash=StyledSpriteCodec.sha(json.writeValueAsBytes(canonical));
        jdbc.sql("SELECT id FROM shelter.asset_jobs WHERE id=:j FOR UPDATE").param("j",id).query(UUID.class).optional().orElseThrow(()->new AssetException(404,"ASSET_NOT_FOUND"));
        var job=styled.read(subject,dog,id);assets.valid(id,true);
        var priorHashes=jdbc.sql("SELECT DISTINCT request_sha256 FROM shelter.styled_learning_recoveries WHERE job_id=:j AND request_id=:request")
            .param("j",id).param("request",requestId).query(String.class).list();
        if(priorHashes.stream().anyMatch(prior->!prior.equals(hash)))throw new AssetException(409,"LEARNING_REQUEST_REUSED");
        var existing=jdbc.sql("SELECT request_sha256 FROM shelter.styled_learning_recoveries WHERE job_id=:j AND request_sha256=:h")
            .param("j",id).param("h",hash).query(String.class).list();
        if(existing.size()==expected.size())return job;
        if(!job.status().equals("REVIEW") || !job.complete() || job.qualityPolicy()==null
            || job.qualityPolicy().path("referenceOnly").asBoolean()
            || !StyledSpriteCodec.qualityRulesSha().equals(job.qualityPolicy().path("rulesSha256").asText()))throw new AssetException(409,"LEARNING_RECOVERY_NOT_ALLOWED");
        var seeds=job.steps().getFirst().result();
        if(job.seedReview()==null || !seeds.path("hashes").equals(job.seedReview().path("hashes")) || !seeds.path("hashes").equals(body.path("expectedSeedHashes")))
            throw new AssetException(409,"SEED_REVIEW_STALE");
        for(var entry:expected.properties()) {
            var step=job.steps().stream().filter(s->s.label().equals(entry.getKey())).findFirst().orElseThrow(AssetException::invalid);
            if(!eligible(step,job.qualityPolicy()) || !entry.getValue().asText().equals(step.result().path("sha256").asText()))throw new AssetException(409,"LEARNING_RECOVERY_STALE");
            if(jdbc.sql("SELECT count(*) FROM shelter.styled_learning_recoveries WHERE job_id=:j AND label=:l").param("j",id).param("l",step.label()).query(Integer.class).single()>0)
                throw new AssetException(409,"LEARNING_RECOVERY_ALREADY_REQUESTED");
            insert(id,step,seeds,job.qualityPolicy(),requestId,hash);
        }
        return styled.read(subject,dog,id);
    }
    @Transactional public void observe(StyledAssetStore.Work w,JsonNode report,JsonNode result,JsonNode seeds) {
        if(w.qualityPolicy()==null || !Set.of(VERSION,LEGACY_VERSION).contains(w.qualityPolicy().path("learningRecovery").asText()) || w.status().equals("CHECKING"))return;
        if(!styled.authorized(w))return;
        var step=new StyledAssetStore.Step(w.label(),w.action(),w.direction(),"SUCCEEDED",result,report,w.repairCount(),null);
        if((w.action().equals("IDLE") || VERSION.equals(w.qualityPolicy().path("learningRecovery").asText())) && eligible(step,w.qualityPolicy()))insert(w.id(),step,seeds,w.qualityPolicy(),null,null);
    }
    private boolean eligible(StyledAssetStore.Step step,JsonNode policy) {
        return ACTIONS.contains(step.action()) && step.repairCount()>=2 && step.repairCount()<=(step.action().equals("IDLE")?4:3) && step.result()!=null && step.qualityReport()!=null
            && !step.qualityReport().path("passed").asBoolean() && !step.qualityReport().path("issues").isEmpty()
            && step.result().path("sha256").asText().equals(step.qualityReport().path("inputSha256").asText())
            && policy.path("rulesSha256").asText().equals(step.qualityReport().path("rulesSha256").asText());
    }
    private void insert(UUID job,StyledAssetStore.Step step,JsonNode seeds,JsonNode policy,UUID requestId,String request) {
        jdbc.sql("""
            INSERT INTO shelter.styled_learning_recoveries(job_id,label,rules_sha256,seed_hashes,source_sha256,source_repair_count,request_id,request_sha256,reason)
            VALUES (:j,:l,:r,CAST(:s AS jsonb), :h,:n,:requestId,:request,'NO_VALIDATED_RULE') ON CONFLICT(job_id,label) DO NOTHING
            """).param("j",job).param("l",step.label()).param("r",policy.path("rulesSha256").asText()).param("s",json.writeValueAsString(seeds.path("hashes")))
            .param("h",step.result().path("sha256").asText()).param("n",step.repairCount()).param("requestId",requestId).param("request",request).update();
    }
    @Transactional public Work claim() {
        jdbc.sql("UPDATE shelter.styled_learning_recoveries SET state='FAILED',reason='REFERENCE_REVIEW_INTERRUPTED',lease_token=NULL,lease_until=NULL WHERE state='CHECKING_REFERENCE' AND lease_until<now()").update();
        var row=jdbc.sql("""
            SELECT r.job_id,r.label FROM shelter.styled_learning_recoveries r JOIN shelter.asset_jobs j ON j.id=r.job_id
            WHERE r.state IN ('WAITING_EVIDENCE','WAITING_RULE') AND j.status='REVIEW' AND r.next_run_at<=now()
             AND (r.lease_until IS NULL OR r.lease_until<now()) ORDER BY r.next_run_at,r.job_id,r.label
            FOR UPDATE OF j,r SKIP LOCKED LIMIT 1
            """).query((r,n)->Map.entry(r.getObject(1,UUID.class),r.getString(2))).optional();
        if(row.isEmpty())return null;UUID job=row.get().getKey();String label=row.get().getValue();UUID token=UUID.randomUUID();
        jdbc.sql("UPDATE shelter.styled_learning_recoveries SET lease_token=:t,lease_until=now()+interval '5 minutes' WHERE job_id=:j AND label=:l")
            .param("t",token).param("j",job).param("l",label).update();
        var w=jdbc.sql("""
            SELECT j.dog_id,j.quality_policy::text,b.result::text,s.direction,s.result::text,s.quality_report::text,r.reference_report::text,s.action
            FROM shelter.styled_learning_recoveries r JOIN shelter.asset_jobs j ON j.id=r.job_id
            JOIN shelter.styled_asset_steps s ON s.job_id=r.job_id AND s.label=r.label
            JOIN shelter.styled_asset_steps b ON b.job_id=r.job_id AND b.label='character' WHERE r.job_id=:j AND r.label=:l
            """).param("j",job).param("l",label).query((r,n)->new Work(job,r.getObject(1,UUID.class),label,token,r.getString(8),r.getString(4),
                node(r.getString(2)),node(r.getString(3)),node(r.getString(5)),node(r.getString(6)),node(r.getString(7)))).single();
        return authorized(w)?w:null;
    }
    @Transactional public boolean authorized(Work w) {
        if(!owned(w))return false;
        try {assets.valid(w.job(),true);}catch(AssetException e){finish(w,"DISABLED","SOURCE_PERMISSION_WITHDRAWN");return false;}
        int valid=jdbc.sql("""
            SELECT count(*) FROM shelter.styled_learning_recoveries r JOIN shelter.asset_jobs j ON j.id=r.job_id
            JOIN shelter.styled_asset_steps s ON s.job_id=r.job_id AND s.label=r.label
            JOIN shelter.styled_asset_steps b ON b.job_id=r.job_id AND b.label='character'
            WHERE r.job_id=:j AND r.label=:l AND j.status='REVIEW' AND s.status='SUCCEEDED'
              AND r.rules_sha256=:rules AND j.quality_policy->>'rulesSha256'=:rules
              AND r.seed_hashes=b.result->'hashes' AND r.seed_hashes=j.seed_review->'hashes'
              AND r.source_sha256=s.result->>'sha256' AND r.source_repair_count=s.repair_count
              AND s.quality_report->>'passed'='false' AND s.quality_report->>'inputSha256'=r.source_sha256
              AND s.quality_report->>'rulesSha256'=r.rules_sha256
            """).param("j",w.job()).param("l",w.label()).param("rules",StyledSpriteCodec.qualityRulesSha()).query(Integer.class).single();
        if(valid!=1){finish(w,"STALE","SNAPSHOT_CHANGED");return false;}
        var baseReport=jdbc.sql("SELECT quality_report::text FROM shelter.styled_asset_steps WHERE job_id=:j AND label='character'")
            .param("j",w.job()).query(String.class).optional().map(this::node).orElse(null);
        if(!StyledSeedQualityAgent.passed(baseReport,w.seeds().path("hashes"),w.policy())){finish(w,"STALE","SEED_QUALITY_REVIEW_REQUIRED");return false;}
        return true;
    }
    @Transactional public boolean hasPositive(Work w) {
        if(!authorized(w))return false;
        var jobs=jdbc.sql("SELECT DISTINCT job_id FROM shelter.styled_quality_examples WHERE action=:a AND direction=:d AND tail=:t AND rules_sha256=:r AND passed")
            .param("a",w.action()).param("d",w.direction()).param("t",w.policy().at("/contract/tailCarriage").asText()).param("r",StyledSpriteCodec.qualityRulesSha()).query(UUID.class).list();
        for(UUID job:jobs)try{assets.valid(job,false);return true;}catch(AssetException revoked){/* Ignore unavailable evidence. */}
        return false;
    }
    @Transactional public JsonNode newLessons(Work w) {
        if(!authorized(w))return json.createArrayNode();
        return json.valueToTree(jdbc.sql("""
            SELECT l.id,l.candidate_sha256,l.issue FROM shelter.styled_quality_lessons l
            JOIN shelter.styled_asset_steps s ON s.job_id=:j AND s.label=:label
            WHERE l.status='ACTIVE' AND l.action=:a AND l.direction=:d AND l.tail=:t AND l.rules_sha256=:r
              AND s.quality_report->'issues' @> jsonb_build_array(l.issue)
              AND NOT EXISTS(SELECT 1 FROM jsonb_array_elements(s.learned_lessons) old WHERE old->>'sha256'=l.candidate_sha256)
            ORDER BY l.id
            """).param("j",w.job()).param("label",w.label()).param("a",w.action()).param("d",w.direction()).param("t",w.policy().at("/contract/tailCarriage").asText())
            .param("r",StyledSpriteCodec.qualityRulesSha()).query((r,n)->Map.of("id",r.getObject(1,UUID.class).toString(),"sha256",r.getString(2),"issue",r.getString(3))).list());
    }
    /** Old positive pixels are evidence candidates, never copied across rule versions as accepted labels. */
    @Transactional public Reference historical(Work w) {
        if(!authorized(w))return null;
        var candidates=jdbc.sql("""
            SELECT e.id,e.job_id,j.dog_id,e.label,e.result::text,e.seeds::text
            FROM shelter.styled_quality_examples e JOIN shelter.asset_jobs j ON j.id=e.job_id
            WHERE e.action=:a AND e.direction=:d AND e.tail=:t AND e.passed AND e.rules_sha256<>:r
              AND e.input_sha256=e.result->>'sha256' AND j.seed_review->'hashes'=e.seeds->'hashes'
              AND j.status NOT IN ('CANCELLED','REJECTED') ORDER BY e.created_at DESC LIMIT 16
            """).param("a",w.action()).param("d",w.direction()).param("t",w.policy().at("/contract/tailCarriage").asText())
            .param("r",StyledSpriteCodec.qualityRulesSha()).query((r,n)->new Reference(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getObject(3,UUID.class),r.getString(4),node(r.getString(5)),node(r.getString(6)))).list();
        for(var reference:candidates)if(referenceAllowed(w,reference))return reference;return null;
    }
    @Transactional public boolean referenceAllowed(Work w,Reference reference) {
        if(!authorized(w))return false;
        try{assets.valid(reference.job(),true);}catch(AssetException denied){return false;}
        return jdbc.sql("""
            SELECT count(*) FROM shelter.styled_quality_examples e JOIN shelter.asset_jobs j ON j.id=e.job_id
            WHERE e.id=:id AND e.job_id=:j AND e.passed AND e.result=CAST(:result AS jsonb) AND e.seeds=CAST(:seeds AS jsonb)
              AND j.seed_review->'hashes'=e.seeds->'hashes' AND j.status NOT IN ('CANCELLED','REJECTED')
            """).param("id",reference.id()).param("j",reference.job()).param("result",json.writeValueAsString(reference.result()))
            .param("seeds",json.writeValueAsString(reference.seeds())).query(Integer.class).single()==1;
    }
    @Transactional public void historicalEvidence(Work w,Reference reference,JsonNode report) {
        if(!referenceAllowed(w,reference))throw new AssetException(409,"LEARNING_EVIDENCE_CHANGED");
        if(!reference.result().path("sha256").equals(report.path("inputSha256"))
            || !StyledSpriteCodec.qualityRulesSha().equals(report.path("rulesSha256").asText()))throw new AssetException(409,"LEARNING_REFERENCE_REPORT_INVALID");
        jdbc.sql("UPDATE shelter.styled_learning_recoveries SET reference_result=CAST(:result AS jsonb),reference_report=CAST(:report AS jsonb) WHERE job_id=:j AND label=:l")
            .param("result",json.writeValueAsString(reference.result())).param("report",json.writeValueAsString(report)).param("j",w.job()).param("l",w.label()).update();
        if(report.path("passed").asBoolean())jdbc.sql("""
            INSERT INTO shelter.styled_quality_examples(job_id,label,action,direction,tail,rules_sha256,input_sha256,result,seeds,report,passed)
            SELECT job_id,label,action,direction,tail,:rules,input_sha256,result,seeds,CAST(:report AS jsonb),true
            FROM shelter.styled_quality_examples WHERE id=:id ON CONFLICT(job_id,label,input_sha256,rules_sha256) DO NOTHING
            """).param("rules",StyledSpriteCodec.qualityRulesSha()).param("report",json.writeValueAsString(report)).param("id",reference.id()).update();
    }
    @Transactional public void startReference(Work w) {
        if(!authorized(w))throw new AssetException(409,"LEARNING_RECOVERY_STALE");
        jdbc.sql("UPDATE shelter.styled_learning_recoveries SET state='CHECKING_REFERENCE',reason=:reason WHERE job_id=:j AND label=:l")
            .param("reason",w.action().equals("IDLE")?"CHECKING_APPROVED_IDLE_REFERENCE":"REVALIDATING_HISTORICAL_MOTION")
            .param("j",w.job()).param("l",w.label()).update();
    }
    /** Preserve the report even when no positive example can be accepted. Never promote a conflicting old label. */
    @Transactional public void evidence(Work w,JsonNode result,JsonNode report,boolean reference) {
        if(!authorized(w))return;
        if(reference)jdbc.sql("UPDATE shelter.styled_learning_recoveries SET reference_result=CAST(:result AS jsonb),reference_report=CAST(:report AS jsonb) WHERE job_id=:j AND label=:l")
            .param("result",json.writeValueAsString(result)).param("report",json.writeValueAsString(report)).param("j",w.job()).param("l",w.label()).update();
        if(report.path("passed").asBoolean())jdbc.sql("""
            INSERT INTO shelter.styled_quality_examples(job_id,label,action,direction,tail,rules_sha256,input_sha256,result,seeds,report,passed)
            VALUES (:j,:l,:a,:d,:t,:rules,:h,CAST(:result AS jsonb),CAST(:seeds AS jsonb),CAST(:report AS jsonb),true)
            ON CONFLICT(job_id,label,input_sha256,rules_sha256) DO NOTHING
            """).param("j",w.job()).param("l",w.label()).param("a",w.action()).param("d",w.direction()).param("t",w.policy().at("/contract/tailCarriage").asText())
            .param("rules",StyledSpriteCodec.qualityRulesSha()).param("h",result.path("sha256").asText()).param("result",json.writeValueAsString(result))
            .param("seeds",json.writeValueAsString(w.seeds())).param("report",json.writeValueAsString(report)).update();
    }
    @Transactional public void waitForRule(Work w,String reason) {
        if(!authorized(w))return;
        var states=jdbc.sql("SELECT status FROM shelter.styled_quality_lessons WHERE source_job_id=:j AND source_label=:l AND rules_sha256=:r")
            .param("j",w.job()).param("l",w.label()).param("r",StyledSpriteCodec.qualityRulesSha()).query(String.class).list();
        if(!reason.equals("NO_APPROVED_MOTION_REFERENCE")) {
            if(states.isEmpty())reason="NO_MATCHING_RULE_CANDIDATE";
            else if(states.stream().noneMatch(s->Set.of("WAITING_EVIDENCE","CANDIDATE","PROPOSING","VALIDATING","ACTIVE").contains(s)))reason="RULE_VALIDATION_FAILED_OR_DISABLED";
        }
        finish(w,reason.equals("NO_APPROVED_MOTION_REFERENCE")?"WAITING_EVIDENCE":"WAITING_RULE",reason);
    }
    @Transactional public void stop(Work w,String state,String reason) {if(owned(w))finish(w,state,reason);}
    @Transactional public void schedule(Work w,JsonNode required) {
        if(!authorized(w) || required.isEmpty() || !required.equals(newLessons(w)))return;
        jdbc.sql("""
            UPDATE shelter.styled_asset_steps SET attempt_history=attempt_history || jsonb_build_array(jsonb_build_object(
              'learningRecovery',true,'providerJobId',provider_job_id,'submittedAt',submitted_at,'requestSha256',request_sha256,
              'result',result,'quality',quality_report,'repairCount',repair_count,'learnedLessons',learned_lessons)),
              repair_count=repair_count+1,status='PENDING',provider_job_id=NULL,submitted_at=NULL,request_sha256=NULL,
              provider_result=NULL,result=NULL,learned_lessons='[]' WHERE job_id=:j AND label=:l
            """).param("j",w.job()).param("l",w.label()).update();
        jdbc.sql("UPDATE shelter.styled_learning_recoveries SET required_lessons=CAST(:lessons AS jsonb),state='QUEUED',reason='VALIDATED_NEW_RULE_READY',lease_token=NULL,lease_until=NULL,updated_at=now() WHERE job_id=:j AND label=:l")
            .param("lessons",json.writeValueAsString(required)).param("j",w.job()).param("l",w.label()).update();
        jdbc.sql("UPDATE shelter.asset_jobs SET status='QUEUED',failure_code=NULL,next_run_at=now(),lease_token=NULL,lease_until=NULL WHERE id=:j")
            .param("j",w.job()).update();
    }
    private boolean owned(Work w) {return jdbc.sql("SELECT r.job_id FROM shelter.styled_learning_recoveries r JOIN shelter.asset_jobs j ON j.id=r.job_id WHERE r.job_id=:j AND r.label=:l AND r.lease_token=:t AND r.lease_until>now() FOR UPDATE OF j,r")
        .param("j",w.job()).param("l",w.label()).param("t",w.token()).query(UUID.class).optional().isPresent();}
    private void finish(Work w,String state,String reason) {
        jdbc.sql("UPDATE shelter.styled_learning_recoveries SET state=:s,reason=:r,lease_token=NULL,lease_until=NULL,next_run_at=now()+interval '30 seconds',updated_at=now() WHERE job_id=:j AND label=:l")
            .param("s",state).param("r",reason).param("j",w.job()).param("l",w.label()).update();
    }
    private JsonNode node(String text){return text==null?null:json.readTree(text);}
}
