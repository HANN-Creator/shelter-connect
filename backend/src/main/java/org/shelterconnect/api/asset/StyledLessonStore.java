package org.shelterconnect.api.asset;

import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.shelterconnect.api.auth.AccountService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Durable lesson queue. No network/model call runs inside these transactions. */
@Service
public class StyledLessonStore {
    static final String REVISION_VERSION="validated-rule-rewrite-v1";
    public record Example(UUID id,UUID jobId,String action,String direction,String tail,String rulesSha256,
                          String inputSha256,JsonNode result,JsonNode seeds,JsonNode report,boolean passed) {}
    public record Work(UUID id,UUID token,String status,String issue,JsonNode scope,JsonNode candidate,List<Example> examples) {}
    private final JdbcClient jdbc;private final JsonMapper json;private final AssetStore assets;private final AccountService accounts;private final org.shelterconnect.api.chat.AiProperties ai;
    public StyledLessonStore(JdbcClient jdbc,JsonMapper json,AssetStore assets,AccountService accounts,org.shelterconnect.api.chat.AiProperties ai){this.jdbc=jdbc;this.json=json;this.assets=assets;this.accounts=accounts;this.ai=ai;}
    @Transactional public void record(StyledAssetStore.Work w,JsonNode report,JsonNode result,JsonNode seeds) {
        if(w.qualityPolicy()==null || !report.path("passed").isBoolean())return;
        // Unknown anatomy is recorded on the job, never mislabeled as a negative training example.
        // Mixed reports are also withheld until observations resolve; confirmed directions can still be repaired.
        if(w.character() && (StyledTailAnatomy.unresolved(report) || StyledCoatReview.unresolved(report)))return;
        if(StyledMotionReview.unresolved(report))return;
        if(!ownedAsset(w))return;assets.valid(w.id(),true);
        String tail=tail(w),direction=direction(w);
        String input=w.character()?report.path("inputSha256").asText():result.path("sha256").asText();
        if(w.character()) {
            if(!input.equals(StyledSeedQualityAgent.hashBinding(seeds.path("hashes")))
                || !report.path("photoSha256").asText().matches("[a-f0-9]{64}"))throw new AssetException(409,"LESSON_EVIDENCE_CHANGED");
            seeds=seeds.deepCopy();
            ((tools.jackson.databind.node.ObjectNode)seeds).set("photo",json.valueToTree(Map.of("dogId",w.dogId(),"bucket",w.bucket(),"key",w.key(),"sha256",report.path("photoSha256").asText())));
        }
        if(!StyledQualityAgent.TAILS.contains(tail))return;
        String rules=w.qualityPolicy().path("rulesSha256").asText();
        UUID example=jdbc.sql("""
            INSERT INTO shelter.styled_quality_examples(job_id,label,action,direction,tail,rules_sha256,input_sha256,result,seeds,report,passed)
            VALUES (:j,:l,:a,:d,:t,:r,:h,CAST(:result AS jsonb),CAST(:seeds AS jsonb),CAST(:report AS jsonb),:passed)
            ON CONFLICT(job_id,label,input_sha256,rules_sha256) DO NOTHING RETURNING id
            """).param("j",w.id()).param("l",w.label()).param("a",w.action()).param("d",direction).param("t",tail)
            .param("r",rules).param("h",input).param("result",json.writeValueAsString(result))
            .param("seeds",json.writeValueAsString(seeds)).param("report",json.writeValueAsString(report))
            .param("passed",report.path("passed").asBoolean()).query(UUID.class).optional().orElse(null);
        if(example==null || report.path("passed").asBoolean())return;
        for(var issue:report.path("issues"))if((w.character()?StyledLessonAgent.SEED_ISSUES:StyledLessonAgent.ISSUES).contains(issue.asText())) {
            var id=jdbc.sql("""
                INSERT INTO shelter.styled_quality_lessons(source_example_id,source_job_id,source_label,action,direction,tail,issue,rules_sha256)
                VALUES (:e,:j,:l,:a,:d,:t,:i,:r) ON CONFLICT(source_job_id,source_label,issue,rules_sha256) DO NOTHING RETURNING id
                """).param("e",example).param("j",w.id()).param("l",w.label()).param("a",w.action()).param("d",direction)
                .param("t",tail).param("i",issue.asText()).param("r",rules).query(UUID.class).optional();
            id.ifPresent(value->event(value,"OBSERVED",Map.of("exampleId",example,"issue",issue.asText(),"status","WAITING_EVIDENCE")));
        }
    }
    @Transactional public Work claim() {
        for(var id:jdbc.sql("""
            UPDATE shelter.styled_quality_lessons SET status='FAILED',lease_token=NULL,lease_until=NULL,updated_at=now()
            WHERE status IN ('PROPOSING','VALIDATING') AND lease_until<now() RETURNING id
            """).query(UUID.class).list())event(id,"INTERRUPTED",Map.of("reason","Model outcome unknown; no automatic duplicate call"));
        for(var id:jdbc.sql("""
            UPDATE shelter.styled_quality_lessons SET status='STALE',lease_token=NULL,lease_until=NULL,updated_at=now()
            WHERE rules_sha256<>:r AND status IN ('WAITING_EVIDENCE','CANDIDATE','ACTIVE') RETURNING id
            """).param("r",StyledSpriteCodec.qualityRulesSha()).query(UUID.class).list())event(id,"STALE",Map.of());
        var row=jdbc.sql("""
            SELECT l.* FROM shelter.styled_quality_lessons l
            WHERE l.status IN ('WAITING_EVIDENCE','CANDIDATE') AND l.rules_sha256=:r
              AND EXISTS(SELECT 1 FROM shelter.styled_quality_examples e WHERE e.action=l.action AND e.direction=l.direction
                AND e.tail=l.tail AND e.rules_sha256=l.rules_sha256 AND e.passed
                AND (e.action<>'BASE' OR EXISTS(SELECT 1 FROM shelter.asset_jobs j WHERE j.id=e.job_id
                  AND j.seed_review->'hashes'=e.seeds->'hashes' AND j.status NOT IN ('REJECTED','CANCELLED'))))
            ORDER BY l.created_at,l.id FOR UPDATE OF l SKIP LOCKED LIMIT 1
            """).param("r",StyledSpriteCodec.qualityRulesSha()).query((r,n)->Map.of("id",r.getObject("id",UUID.class),
                "source",r.getObject("source_example_id",UUID.class),"issue",r.getString("issue"),"candidate",Optional.ofNullable(r.getString("candidate")))).optional();
        if(row.isEmpty())return null;
        UUID id=(UUID)row.get().get("id");String issue=(String)row.get().get("issue");
        Example source=example((UUID)row.get().get("source"));
        if(!valid(source)){terminal(id,"DISABLED","Source permission withdrawn");return null;}
        var others=jdbc.sql("""
            SELECT e.id FROM shelter.styled_quality_examples e WHERE action=:a AND direction=:d AND tail=:t AND rules_sha256=:r
              AND (action<>'BASE' OR NOT passed OR EXISTS(SELECT 1 FROM shelter.asset_jobs j WHERE j.id=e.job_id
                AND j.seed_review->'hashes'=e.seeds->'hashes' AND j.status NOT IN ('REJECTED','CANCELLED')))
              AND input_sha256<>:h AND (passed OR report->'issues' @> CAST(:issue AS jsonb))
            ORDER BY passed DESC,created_at DESC LIMIT 32
            """).param("a",source.action()).param("d",source.direction()).param("t",source.tail()).param("r",source.rulesSha256())
            .param("h",source.inputSha256()).param("issue",json.writeValueAsString(List.of(issue))).query(UUID.class).list();
        var examples=new ArrayList<Example>();examples.add(source);int good=0,bad=1;var hashes=new HashSet<String>();hashes.add(source.inputSha256());
        for(UUID other:others) {var e=example(other);if(!hashes.add(e.inputSha256()) || !valid(e))continue;
            if(e.passed() && good<2){examples.add(e);good++;}else if(!e.passed() && bad<2){examples.add(e);bad++;}
            if(examples.size()==4)break;
        }
        if(good==0){terminal(id,"DISABLED","No authorized positive evidence");return null;}
        // Labels are withheld from the model and ordering does not reveal the expected verdict.
        examples.sort(Comparator.comparing(e->e.id().toString()));
        @SuppressWarnings("unchecked") var candidate=(Optional<String>)row.get().get("candidate");
        String status=candidate.isPresent()?"VALIDATING":"PROPOSING";UUID token=UUID.randomUUID();
        jdbc.sql("UPDATE shelter.styled_quality_lessons SET status=:s,lease_token=:t,lease_until=now()+interval '5 minutes',validation_examples=CAST(:e AS jsonb),updated_at=now() WHERE id=:id")
            .param("s",status).param("t",token).param("e",json.writeValueAsString(examples.stream().map(e->Map.of("id",e.id(),"sha256",e.inputSha256())).toList())).param("id",id).update();
        event(id,status,Map.of("caseCount",examples.size()));
        var scope=json.createObjectNode().put("action",source.action()).put("direction",source.direction()).put("tail",source.tail()).put("issue",issue)
            .put("sourceExampleId",source.id().toString());
        if(status.equals("PROPOSING"))scope.set("revisionFeedback",lesson(id).path("revisionFeedback"));
        return new Work(id,token,status,issue,scope,candidate.map(json::readTree).orElse(null),List.copyOf(examples));
    }
    @Transactional public boolean authorized(Work w) {
        return owned(w) && w.examples().stream().allMatch(this::valid);
    }
    @Transactional public void proposed(Work w,JsonNode candidate) {
        if(!authorized(w))return;StyledLessonAgent.validateText(candidate);
        jdbc.sql("UPDATE shelter.styled_quality_lessons SET candidate=CAST(:c AS jsonb),candidate_sha256=:h,status='CANDIDATE',lease_token=NULL,lease_until=NULL,updated_at=now() WHERE id=:id")
            .param("c",json.writeValueAsString(candidate)).param("h",StyledSpriteCodec.sha(json.writeValueAsBytes(candidate))).param("id",w.id()).update();
        event(w.id(),"PROPOSED",Map.of("modelGenerated",true,"model",ai.model(),"version",StyledLessonAgent.VERSION,
            "candidate",candidate,"candidateSha256",StyledSpriteCodec.sha(json.writeValueAsBytes(candidate))));
    }
    @Transactional public void validated(Work w,JsonNode verdict) {
        if(!authorized(w))return;
        boolean pass=verdict.path("safeAndGeneral").asBoolean();var results=new HashMap<String,JsonNode>();
        var feedback=new TreeSet<String>();if(!pass)feedback.add("CONFLICTS_WITH_IMMUTABLE_RULES_OR_NOT_GENERAL");
        for(var v:verdict.path("cases"))results.put(v.path("key").asText(),v);
        for(int i=0;i<w.examples().size();i++) {
            var e=w.examples().get(i);var v=results.get("CASE_"+i);
            if(v==null || v.path("violates").asBoolean()==e.passed()) {
                pass=false;feedback.add(e.passed()?"REJECTS_KNOWN_GOOD_EXAMPLE":"MISSES_RECORDED_DEFECT");
            }
            if(e.action().equals("BASE")) {
                if(v==null || !v.path("directions").isArray())pass=false;
                if(!e.passed() && v!=null && w.issue().equals("CANVAS_CLIPPING"))for(var direction:e.report().path("edgeDirections"))
                    if(v.path("directions").valueStream().noneMatch(n->n.asText().equals(direction.asText())))pass=false;
                if(!e.passed() && v!=null && w.issue().equals("SEED_MOTION_MARGIN"))for(var direction:e.report().path("marginDirections"))
                    if(v.path("directions").valueStream().noneMatch(n->n.asText().equals(direction.asText())))pass=false;
                continue;
            }
            // Recorded deterministic findings, including frame 8, cannot be omitted by the replay model.
            String frames=switch(w.issue()){case "CANVAS_CLIPPING"->"edgeFrames";case "IDLE_MOTION"->"idleMotionFrames";case "DETACHED_PIXELS"->"detachedFrames";case "TAIL_CARRIAGE"->"silhouetteFrames";default->"none";};
            if(!e.passed() && v!=null)for(var index:e.report().path(frames))
                if(v.path("frames").valueStream().noneMatch(n->n.asInt()==index.asInt())) {
                    pass=false;feedback.add("MISSES_MEASURED_DEFECT_FRAME");
                }
        }
        var report=json.createObjectNode();report.put("passed",pass);report.put("version",StyledLessonAgent.VERSION);
        report.put("validatedAt",Instant.now().toString());report.put("model",ai.model());report.set("replay",verdict);
        report.put("scopeLimited",true);report.put("freshGenerationVerified",false);
        // Record the failed candidate/verdict/bindings BEFORE replacing the candidate.
        event(w.id(),pass?"ACTIVATED":"REJECTED",Map.of("passed",pass,"candidate",w.candidate(),
            "candidateSha256",StyledSpriteCodec.sha(json.writeValueAsBytes(w.candidate())),"validation",report,
            "examples",w.examples().stream().map(e->Map.of("id",e.id(),"sha256",e.inputSha256())).toList()));
        boolean revise=!pass && jdbc.sql("""
            SELECT count(*) FROM shelter.styled_quality_lessons l JOIN shelter.asset_jobs j ON j.id=l.source_job_id
            WHERE l.id=:id AND l.revision_count<2 AND j.quality_policy->>'lessonRevision'=:version
            """).param("id",w.id()).param("version",REVISION_VERSION).query(Integer.class).single()==1;
        jdbc.sql("UPDATE shelter.styled_quality_lessons SET validation=CAST(:v AS jsonb),status=:s,lease_token=NULL,lease_until=NULL,updated_at=now() WHERE id=:id")
            .param("v",json.writeValueAsString(report)).param("s",pass?"ACTIVE":"REJECTED").param("id",w.id()).update();
        if(revise) {
            var revision=Map.of("previousCandidate",w.candidate(),"failedChecks",feedback,
                "reviewReason",verdict.path("reason").asText(),"instruction","Narrow or correct the rule while retaining all immutable checks.");
            jdbc.sql("""
                UPDATE shelter.styled_quality_lessons SET revision_count=revision_count+1,revision_feedback=CAST(:f AS jsonb),
                  candidate=NULL,candidate_sha256=NULL,status='WAITING_EVIDENCE' WHERE id=:id
                """).param("f",json.writeValueAsString(revision)).param("id",w.id()).update();
            event(w.id(),"REVISION_QUEUED",Map.of("version",REVISION_VERSION,"feedback",revision));
        }
    }
    @Transactional public void failed(Work w,String code) {if(owned(w))terminal(w.id(),"FAILED",code);}
    /** Pin every applicable active rule; the composer, not selection, handles the provider text budget. */
    @Transactional public JsonNode pin(StyledAssetStore.Work w) {
        var selected=json.createArrayNode();if(!ownedAsset(w) || w.qualityPolicy()==null)return selected;assets.valid(w.id(),true);
        var ids=jdbc.sql("""
            SELECT id FROM shelter.styled_quality_lessons WHERE status='ACTIVE' AND action=:a AND direction=:d AND tail=:t AND rules_sha256=:r
            ORDER BY id
            """).param("a",w.action()).param("d",direction(w)).param("t",tail(w)).param("r",StyledSpriteCodec.qualityRulesSha()).query(UUID.class).list();
        for(UUID id:ids) {
            var row=lesson(id);
            if(!evidencePermitted(row)){terminal(id,"DISABLED","Evidence permission withdrawn");continue;}
            var c=row.path("candidate");StyledLessonAgent.validateText(c);
            selected.add(json.valueToTree(Map.of("id",id.toString(),"sha256",row.path("candidateSha256").asText(),"issue",row.path("issue").asText(),
                "prevention",c.path("prevention").asText(),"criterion",c.path("criterion").asText(),"action",w.action(),"direction",direction(w),
                "tail",tail(w),"rulesSha256",StyledSpriteCodec.qualityRulesSha())));
        }
        jdbc.sql("UPDATE shelter.styled_asset_steps SET learned_lessons=CAST(:l AS jsonb) WHERE job_id=:j AND label=:label AND status='PENDING'")
            .param("l",json.writeValueAsString(selected)).param("j",w.id()).param("label",w.label()).update();
        return pinned(w); // Use the durable JSON representation for all hashes and the next quality review.
    }
    @Transactional public boolean authorized(StyledAssetStore.Work w,JsonNode selected) {
        if(!ownedAsset(w) || !selected.equals(pinned(w)))return false;assets.valid(w.id(),true);
        for(var expected:selected) {
            var row=lesson(UUID.fromString(expected.path("id").asText()));
            if(!row.path("status").asText().equals("ACTIVE") || !row.path("candidateSha256").equals(expected.path("sha256"))
                || !row.path("rulesSha256").asText().equals(StyledSpriteCodec.qualityRulesSha()) || !evidencePermitted(row))return false;
        }
        return true;
    }
    private boolean evidencePermitted(JsonNode row) {
        if(!row.path("validationExamples").isArray() || row.path("validationExamples").isEmpty())return false;
        for(var e:row.path("validationExamples")) {
            try {if(!valid(example(UUID.fromString(e.path("id").asText()))))return false;}
            catch(org.springframework.dao.EmptyResultDataAccessException removed){return false;}
        }
        return true;
    }
    @Transactional(readOnly=true) public JsonNode pinned(StyledAssetStore.Work w) {
        return json.readTree(jdbc.sql("SELECT learned_lessons::text FROM shelter.styled_asset_steps WHERE job_id=:j AND label=:l")
            .param("j",w.id()).param("l",w.label()).query(String.class).single());
    }
    @Transactional public List<JsonNode> list(UUID subject) {
        operator(subject);return jdbc.sql("SELECT id FROM shelter.styled_quality_lessons ORDER BY created_at DESC LIMIT 100").query(UUID.class).list().stream().map(this::lesson).toList();
    }
    @Transactional public JsonNode disable(UUID subject,UUID id,JsonNode body) {
        operator(subject);AssetInput.fields(body,"note");String note=AssetInput.text(body,"note",500);if(note.length()<10)throw AssetException.invalid();
        jdbc.sql("SELECT id FROM shelter.styled_quality_lessons WHERE id=:id FOR UPDATE").param("id",id).query(UUID.class).optional().orElseThrow(()->new AssetException(404,"LESSON_NOT_FOUND"));
        if(!lesson(id).path("status").asText().equals("DISABLED"))terminal(id,"DISABLED",note);
        return lesson(id);
    }
    @Transactional public JsonNode read(UUID subject,UUID id) {
        operator(subject);var node=(tools.jackson.databind.node.ObjectNode)lesson(id);
        node.set("events",json.valueToTree(jdbc.sql("SELECT event,detail::text,created_at FROM shelter.styled_quality_lesson_events WHERE lesson_id=:id ORDER BY created_at,id")
            .param("id",id).query((r,n)->Map.of("event",r.getString("event"),"detail",json.readTree(r.getString("detail")),"at",r.getTimestamp("created_at").toInstant())).list()));
        return node;
    }
    private Example example(UUID id) {
        return jdbc.sql("SELECT * FROM shelter.styled_quality_examples WHERE id=:id").param("id",id).query((r,n)->new Example(id,r.getObject("job_id",UUID.class),
            r.getString("action"),r.getString("direction"),r.getString("tail"),r.getString("rules_sha256"),r.getString("input_sha256"),
            json.readTree(r.getString("result")),json.readTree(r.getString("seeds")),json.readTree(r.getString("report")),r.getBoolean("passed"))).single();
    }
    private JsonNode lesson(UUID id) {
        return jdbc.sql("SELECT * FROM shelter.styled_quality_lessons WHERE id=:id").param("id",id).query((r,n)->{
            var o=json.createObjectNode();o.put("id",id.toString());for(var f:Map.of("status","status","action","action","direction","direction","tail","tail","issue","issue","rulesSha256","rules_sha256","candidateSha256","candidate_sha256").entrySet())o.put(f.getKey(),r.getString(f.getValue()));
            for(var f:Map.of("candidate","candidate","validationExamples","validation_examples","validation","validation","revisionFeedback","revision_feedback").entrySet())o.set(f.getKey(),json.readTree(Optional.ofNullable(r.getString(f.getValue())).orElse("null")));
            o.put("revisionCount",r.getInt("revision_count"));
            o.put("createdAt",r.getTimestamp("created_at").toInstant().toString());o.put("updatedAt",r.getTimestamp("updated_at").toInstant().toString());return (JsonNode)o;
        }).optional().orElseThrow(()->new AssetException(404,"LESSON_NOT_FOUND"));
    }
    private static String direction(StyledAssetStore.Work w){return w.character()?"all":w.direction();}
    private static String tail(StyledAssetStore.Work w){return w.character()?"UNKNOWN":w.qualityPolicy().at("/contract/tailCarriage").asText();}
    private boolean valid(Example e) {try {
        assets.valid(e.jobId(),false);
        if(e.action().equals("BASE") && e.passed() && jdbc.sql("SELECT count(*) FROM shelter.asset_jobs WHERE id=:id AND seed_review->'hashes'=CAST(:hashes AS jsonb) AND status NOT IN ('REJECTED','CANCELLED')")
            .param("id",e.jobId()).param("hashes",json.writeValueAsString(e.seeds().path("hashes"))).query(Integer.class).single()!=1)return false;
        return e.rulesSha256().equals(StyledSpriteCodec.qualityRulesSha());
    }catch(AssetException denied){return false;}}
    private boolean owned(Work w) {return jdbc.sql("SELECT id FROM shelter.styled_quality_lessons WHERE id=:id AND lease_token=:t AND lease_until>now() AND status=:s FOR UPDATE")
        .param("id",w.id()).param("t",w.token()).param("s",w.status()).query(UUID.class).optional().isPresent();}
    private boolean ownedAsset(StyledAssetStore.Work w) {return jdbc.sql("SELECT id FROM shelter.asset_jobs WHERE id=:id AND lease_token=:t AND lease_until>now() AND status='RUNNING' FOR UPDATE")
        .param("id",w.id()).param("t",w.token()).query(UUID.class).optional().isPresent();}
    private void terminal(UUID id,String status,String reason) {
        jdbc.sql("UPDATE shelter.styled_quality_lessons SET status=:s,lease_token=NULL,lease_until=NULL,updated_at=now() WHERE id=:id").param("s",status).param("id",id).update();
        event(id,status,Map.of("reason",reason));
    }
    private void event(UUID id,String event,Object detail) {jdbc.sql("INSERT INTO shelter.styled_quality_lesson_events(lesson_id,event,detail) VALUES (:id,:e,CAST(:d AS jsonb))")
        .param("id",id).param("e",event).param("d",json.writeValueAsString(detail)).update();}
    private void operator(UUID subject){if(!accounts.lockProfile(subject,false).role().equals("OPERATOR"))throw new AssetException(403,"FORBIDDEN");}
}
