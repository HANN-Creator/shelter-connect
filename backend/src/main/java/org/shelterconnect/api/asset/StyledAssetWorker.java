package org.shelterconnect.api.asset;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class StyledAssetWorker {
    private final StyledAssetStore store;private final StyledAssetProvider provider;private final AssetStorage storage;
    private final StyledSpriteCodec codec;private final AssetProperties properties;private final JsonMapper json;private final StyledQualityAgent quality;
    private final StyledLessonStore lessons;
    private final StyledSeedQualityAgent seedQuality;
    private final StyledLearningRecoveryStore recovery;private final StyledLessonPromptComposer prompts;
    public StyledAssetWorker(StyledAssetStore store,StyledAssetProvider provider,AssetStorage storage,StyledSpriteCodec codec,AssetProperties properties,JsonMapper json,StyledQualityAgent quality,StyledLessonStore lessons,StyledSeedQualityAgent seedQuality,StyledLearningRecoveryStore recovery,StyledLessonPromptComposer prompts) {
        this.store=store;this.provider=provider;this.storage=storage;this.codec=codec;this.properties=properties;this.json=json;this.quality=quality;this.lessons=lessons;this.seedQuality=seedQuality;this.recovery=recovery;this.prompts=prompts;
    }
    public void tick() {
        if(!properties.enabled)return;
        var w=store.claim();if(w==null)return;boolean submitted=false;
        try {
            if(!store.authorized(w))return;
            boolean learnedEdit=!store.learningRecovery(w).isEmpty();
            boolean edit=motionEdit(w) || learnedEdit;
            if(w.qualityPolicy()!=null && w.qualityPolicy().has("rulesSha256")
                && !StyledSpriteCodec.qualityRulesSha().equals(w.qualityPolicy().path("rulesSha256").asText()))
                throw new AssetException(409,"QUALITY_RULES_CHANGED");
            if(!w.character() && w.qualityPolicy()!=null && !w.qualityPolicy().has("contract")) {
                if(!store.startContract(w))return;
                var contract=quality.contract(storage.photo(w.dogId(),w.bucket(),w.key()),w.traits());
                store.contract(w,contract);store.defer(w,0);return;
            }
            if(w.status().equals("CHECKING")) {
                if(w.character()) {
                    var seeds=new ArrayList<byte[]>();
                    for(String d:StyledSpriteCodec.DIRECTIONS) {
                        String key=w.result().path("keys").path(d).asText();
                        if(!key.startsWith(w.prefix()+"directions/"))throw new AssetException(409,"STYLED_SEED_CHANGED");
                        byte[] image=storage.asset(key);
                        if(!StyledSpriteCodec.sha(image).equals(w.result().path("hashes").path(d).asText()))
                            throw new AssetException(409,"STYLED_SEED_CHANGED");
                        StyledSpriteCodec.nativeFrame(image);seeds.add(image);
                    }
                    // An explicit rule recheck only judges stored bytes; it never buys a new character.
                    inspectSeeds(w,seeds,w.result(),false);return;
                }
                byte[] sheet=storage.asset(w.result().path("key").asText());
                if(!StyledSpriteCodec.sha(sheet).equals(w.result().path("sha256").asText()))throw new AssetException(409,"STYLED_SHEET_CHANGED");
                var frames=StyledSpriteCodec.frames(sheet);StyledSpriteCodec.sheet(frames,seed(w));
                inspect(w,frames,w.result());return;
            }
            if(w.status().equals("PENDING")) {
                if(!learnedEdit && mirrorRepair(w)) {
                    var plan=continuation(w);var source=store.mirrorSource(w);var base=store.seed(w);
                    if(!base.path("hashes").equals(w.qualityPolicy().at("/repairContinuation/seedHashes")))throw new AssetException(409,"STYLED_SEED_CHANGED");
                    byte[] sheet=storage.asset(source.path("key").asText());
                    if(!StyledSpriteCodec.sha(sheet).equals(plan.path("sourceSha256").asText()))throw new AssetException(409,"STYLED_SHEET_CHANGED");
                    String direction=plan.path("sourceDirection").asText();
                    byte[] origin=storage.asset(base.path("keys").path(direction).asText());
                    if(!StyledSpriteCodec.sha(origin).equals(base.path("hashes").path(direction).asText()))throw new AssetException(409,"STYLED_SEED_CHANGED");
                    var frames=StyledSpriteCodec.mirroredMotion(sheet,origin,seed(w));
                    lessons.pin(w);
                    store.derivedCheckpoint(w,json.valueToTree(Map.of("status","COMPLETED",
                        "frames",frames.stream().map(b->Base64.getEncoder().encodeToString(b)).toList(),
                        "derivation",Map.of("strategy",StyledSpriteCodec.MIRROR_VERSION,"source",source,"seedHashes",base.path("hashes")))));
                    return;
                }
                var policy=w.qualityPolicy()==null?json.createObjectNode():
                        json.valueToTree(Map.of("contract",w.qualityPolicy().path("contract"),"attempt",w.repairCount(),
                            "rulesSha256",w.qualityPolicy().path("rulesSha256").asText(),
                            "issues",w.qualityReport()!=null && w.qualityReport().path("issues").isArray()?w.qualityReport().path("issues"):json.createArrayNode()));
                JsonNode payload=learnedEdit?codec.seedIdle(w.direction(),seed(w),(int)(((long)w.traits().path("seed").asInt()+7919L*w.repairCount())%2147483647)):motionEdit(w)?motionEditPayload(w):w.character()?codec.character(w.dogId(),w.traits(),storage.photo(w.dogId(),w.bucket(),w.key()),policy):
                    codec.motion(w.traits(),w.action(),w.direction(),seed(w),policy);
                var selected=lessons.pin(w);
                if(!selected.isEmpty()) {
                    String description=prompts.describe(w,selected,payload.path("description").asText(),w.character() || edit?2000:1000);
                    ((tools.jackson.databind.node.ObjectNode)payload).put("description",description);
                }
                if(!store.reserve(w,payload))return;
                submitted=true;
                UUID id=edit?provider.editAnimation(payload):provider.submit(w.character(),payload);store.accepted(w,id);return;
            }
            JsonNode result=w.providerResult();
            if(result==null) {
                if(w.submittedAt()==null || w.submittedAt().plusSeconds(7200).isBefore(Instant.now())) { store.fail(w,true,"PROVIDER_WAIT_EXPIRED");return; }
                try { result=provider.poll(w.providerId(),w.character()); }
                catch(AssetProvider.Failure e) { store.defer(w,20);return; }
                if(result.path("status").asText().equals("WAITING")) { store.defer(w,5);return; }
                if(result.path("status").asText().equals("FAILED")) { store.fail(w,false,"PROVIDER_JOB_FAILED");return; }
                if(!result.path("status").asText().equals("COMPLETED"))throw new AssetException(422,"STYLED_RESULT_INVALID");
                store.checkpoint(w,result);
            }
            if(!store.authorized(w))return;
            if(w.character()) {
                Map<String,String> keys=new LinkedHashMap<>(),hashes=new LinkedHashMap<>();
                var seeds=new ArrayList<byte[]>();
                for(String d:StyledSpriteCodec.DIRECTIONS) {
                    byte[] image=StyledPixelLabClient.decode(result.path("directions").path(d).asText());
                    String key=w.prefix()+"directions/"+(w.repairCount()==0?"":"repair-"+w.repairCount()+"/")+d+".png";
                    storage.put(key,image);keys.put(d,key);hashes.put(d,StyledSpriteCodec.sha(image));seeds.add(image);
                }
                var metadata=json.valueToTree(Map.of("keys",keys,"hashes",hashes));
                if(w.qualityPolicy()!=null && w.qualityPolicy().has("seedQualityVersion")) {
                    inspectSeeds(w,seeds,metadata,true);return;
                }
                store.success(w,metadata);
            } else {
                var frames=result.path("frames").valueStream().map(n->StyledPixelLabClient.decode(n.asText())).toList();
                JsonNode rawEdit=null;
                if(edit || mirrorRepair(w)) {
                    byte[] raw=StyledSpriteCodec.rawSheet(frames);String rawKey=w.prefix()+"raw-edits/"+w.label()+"-"+w.repairCount()+".png";
                    storage.put(rawKey,raw);rawEdit=json.valueToTree(Map.of("key",rawKey,"sha256",StyledSpriteCodec.sha(raw)));
                    frames=StyledSpriteCodec.restoreEditPalette(frames,seed(w));
                }
                byte[] sheet=StyledSpriteCodec.sheet(frames,seed(w));
                String key=w.prefix()+"sheets/"+(w.repairCount()==0?"":"repair-"+w.repairCount()+"/")+w.label()+".png";storage.put(key,sheet);
                var spec=StyledSpriteCodec.rules(json).path("actions").path(w.action());
                var metadata=json.valueToTree(Map.of("key",key,"sha256",StyledSpriteCodec.sha(sheet),"frameCount",9,
                    "durationMs",spec.path("durationMs").asInt(),"loop",spec.path("loop").asBoolean()));
                if(rawEdit!=null)((tools.jackson.databind.node.ObjectNode)metadata).set("rawEdit",rawEdit);
                if(result.has("derivation"))((tools.jackson.databind.node.ObjectNode)metadata).set("derivation",result.path("derivation"));
                if(result.has("usage"))((tools.jackson.databind.node.ObjectNode)metadata).set("providerUsage",result.path("usage"));
                inspect(w,frames,metadata);
            }
        } catch(AssetProvider.Failure e) { store.fail(w,e.uncertain,e.code); }
        catch(AssetException e) { store.fail(w,false,e.code); }
        catch(RuntimeException e) { store.fail(w,submitted,"STYLED_WORK_INTERRUPTED"); }
    }
    private void inspectSeeds(StyledAssetStore.Work w,List<byte[]> seeds,JsonNode metadata,boolean allowRepair) {
        byte[] photo=storage.photo(w.dogId(),w.bucket(),w.key());
        if(w.qualityPolicy().path("referenceOnly").asBoolean()
            && !StyledSpriteCodec.sha(photo).equals(w.qualityPolicy().at("/referenceInput/sourcePhotoSha256").asText()))
            throw new AssetException(409,"SOURCE_PHOTO_CHANGED");
        var selected=lessons.pinned(w);String lessonSha=StyledSpriteCodec.sha(json.writeValueAsBytes(selected));
        var report=w.qualityReport();
        if(report==null || !StyledSeedQualityAgent.VERSION.equals(report.path("version").asText())
            || !StyledSeedQualityAgent.binding(seeds).equals(report.path("inputSha256").asText())
            || !StyledSpriteCodec.sha(photo).equals(report.path("photoSha256").asText())
            || !lessonSha.equals(report.path("lessonsSha256").asText())
            || !StyledSpriteCodec.qualityRulesSha().equals(report.path("rulesSha256").asText())) {
            if(!store.startQuality(w))return;
            var checked=selected.isEmpty()?seedQuality.review(photo,seeds):seedQuality.review(photo,seeds,selected);
            var details=(tools.jackson.databind.node.ObjectNode)checked.deepCopy();
            details.put("photoSha256",StyledSpriteCodec.sha(photo));details.put("lessonsSha256",lessonSha);details.set("learnedLessons",selected);
            report=details;store.quality(w,report);
        }
        var evidence=report;
        if(w.qualityPolicy().path("referenceOnly").asBoolean()
            && w.qualityPolicy().at("/referenceInput/assessment").asText().equals("NEGATIVE")) {
            var corrected=(tools.jackson.databind.node.ObjectNode)report.deepCopy();
            corrected.set("aiAssessment",report);corrected.put("passed",false);corrected.put("assessmentSource","HUMAN_NEGATIVE_FEEDBACK");
            corrected.set("humanAssessment",w.qualityPolicy().path("referenceInput"));
            var issues=new TreeSet<String>();report.path("issues").forEach(n->issues.add(n.asText()));
            w.qualityPolicy().at("/referenceInput/issues").forEach(n->issues.add(n.asText()));
            corrected.set("issues",json.valueToTree(issues));evidence=corrected;
        }
        // A supposed positive rejected by vision is unresolved, never repurposed as negative training evidence.
        if(!(w.qualityPolicy().path("referenceOnly").asBoolean()
            && w.qualityPolicy().at("/referenceInput/assessment").asText().equals("POSITIVE") && !report.path("passed").asBoolean()))
            lessons.record(w,evidence,metadata,metadata);
        if(allowRepair && store.retryQuality(w,report,metadata))return;
        store.success(w,metadata);
    }
    private void inspect(StyledAssetStore.Work w,List<byte[]> frames,JsonNode metadata) {
        if(w.qualityPolicy()==null){store.success(w,metadata);return;}
        var report=w.qualityReport();String sha=metadata.path("sha256").asText();
        var selected=lessons.pinned(w);String lessonSha=StyledSpriteCodec.sha(json.writeValueAsBytes(selected));
        List<byte[]> rawFrames=null;
        if(metadata.has("rawEdit")) {
            var source=metadata.path("rawEdit");String key=source.path("key").asText();
            if(!key.startsWith(w.prefix()+"raw-edits/"))throw new AssetException(409,"TAIL_EDIT_INPUT_INVALID");
            byte[] raw=storage.asset(key);
            if(!StyledSpriteCodec.sha(raw).equals(source.path("sha256").asText()))throw new AssetException(409,"STYLED_SHEET_CHANGED");
            rawFrames=StyledSpriteCodec.frames(raw);
        }
        if(report==null || !sha.equals(report.path("inputSha256").asText())
            || !StyledSpriteCodec.qualityRulesSha().equals(report.path("rulesSha256").asText())
            || (!selected.isEmpty() && !lessonSha.equals(report.path("lessonsSha256").asText()))
            || (metadata.has("rawEdit") && !metadata.at("/rawEdit/sha256").asText().equals(report.path("rawEditSha256").asText()))) {
            var base=store.seed(w);var seeds=new ArrayList<byte[]>();
            for(String d:StyledSpriteCodec.DIRECTIONS) {
                var seed=storage.asset(base.path("keys").path(d).asText());
                if(!StyledSpriteCodec.sha(seed).equals(base.path("hashes").path(d).asText()))throw new AssetException(409,"STYLED_SEED_CHANGED");
                seeds.add(seed);
            }
            if(!store.startQuality(w))return;
            var review=(selected.isEmpty()?quality.review(w.qualityPolicy().path("contract"),seeds,frames,w.action(),w.direction()):
                quality.review(w.qualityPolicy().path("contract"),seeds,frames,w.action(),w.direction(),selected)).deepCopy();
            if(metadata.has("rawEdit")) {
                var source=metadata.path("rawEdit");
                var rawReview=selected.isEmpty()?quality.review(w.qualityPolicy().path("contract"),seeds,rawFrames,w.action(),w.direction()):
                    quality.review(w.qualityPolicy().path("contract"),seeds,rawFrames,w.action(),w.direction(),selected);
                var issues=new TreeSet<String>();review.path("issues").forEach(n->issues.add(n.asText()));rawReview.path("issues").forEach(n->issues.add(n.asText()));
                var restored=review.deepCopy();
                var combined=(tools.jackson.databind.node.ObjectNode)review;
                combined.set("restoredReview",restored);
                combined.set("issues",json.valueToTree(issues));combined.put("passed",review.path("passed").asBoolean() && rawReview.path("passed").asBoolean());
                combined.set("rawEditReview",rawReview);combined.put("rawEditSha256",source.path("sha256").asText());
            }
            var details=(tools.jackson.databind.node.ObjectNode)review;details.put("inputSha256",sha);details.put("lessonsSha256",lessonSha);details.set("learnedLessons",selected);
            report=review;store.quality(w,report);
        }
        // Each report labels only its own exact bytes. The combined gate still requires BOTH to pass.
        var seeds=store.seed(w);
        if(metadata.has("rawEdit") && metadata.at("/rawEdit/sha256").asText().equals(sha)
            && report.path("rawEditReview").path("passed").asBoolean()!=report.path("restoredReview").path("passed").asBoolean()) {
            // Identical bytes with contradictory model labels cannot become a positive/negative pair.
            lessons.record(w,report,metadata,seeds);
        } else if(metadata.has("rawEdit")) {
            lessons.record(w,report.path("rawEditReview"),metadata.path("rawEdit"),seeds);
            if(report.has("restoredReview"))lessons.record(w,report.path("restoredReview"),metadata,seeds);
            else if(report.path("rawEditReview").path("passed").asBoolean())lessons.record(w,report,metadata,seeds);
        } else lessons.record(w,report,metadata,seeds);
        if(!store.retryQuality(w,report,metadata)) {
            recovery.observe(w,report,metadata,seeds);
            store.success(w,metadata);
        }
    }
    static boolean tailEdit(StyledAssetStore.Work w) {
        return w.action().equals("TAIL_WAG") && w.repairCount()==2 && w.qualityPolicy()!=null
            && w.qualityPolicy().path("lowTailRepair").asText().equals("REGENERATE_THEN_EDIT_ONCE")
            && w.qualityPolicy().at("/contract/tailCarriage").asText().equals("LOW");
    }
    static boolean idleEdit(StyledAssetStore.Work w) {
        if(w.action().equals("IDLE") && StyledSpriteCodec.SEED_IDLE_VERSION.equals(continuation(w).path("strategy").asText()))return true;
        return w.action().equals("IDLE") && w.repairCount()==2 && w.qualityPolicy()!=null
            && StyledSpriteCodec.IDLE_EDIT_VERSION.equals(w.qualityPolicy().path("idleRepair").asText())
            && w.qualityReport()!=null && w.qualityReport().path("issues").isArray()
            && w.qualityReport().path("issues").valueStream().anyMatch(i->i.asText().equals("IDLE_MOTION"));
    }
    static boolean marginEdit(StyledAssetStore.Work w) {
        return !w.character() && w.repairCount()==2 && w.qualityPolicy()!=null
            && StyledSpriteCodec.MARGIN_EDIT_VERSION.equals(w.qualityPolicy().path("marginRepair").asText())
            && w.qualityReport()!=null && w.qualityReport().path("issues").isArray()
            && w.qualityReport().path("issues").valueStream().anyMatch(i->i.asText().equals("CANVAS_CLIPPING"));
    }
    static boolean motionEdit(StyledAssetStore.Work w) {return tailEdit(w) || idleEdit(w) || marginEdit(w);}
    private static JsonNode continuation(StyledAssetStore.Work w) {
        if(w.qualityPolicy()==null)return tools.jackson.databind.node.MissingNode.getInstance();
        var plan=w.qualityPolicy().at("/repairContinuation/plans/"+w.label());
        boolean valid=w.repairCount()==plan.path("attempt").asInt() && (w.repairCount()==3
            || w.repairCount()==4 && w.action().equals("IDLE") && StyledSpriteCodec.SEED_IDLE_VERSION.equals(plan.path("strategy").asText()));
        return valid?plan:tools.jackson.databind.node.MissingNode.getInstance();
    }
    static boolean mirrorRepair(StyledAssetStore.Work w) {
        return Set.of("IDLE","WALK","SIT").contains(w.action()) && Set.of("west","east").contains(w.direction())
            && StyledSpriteCodec.MIRROR_VERSION.equals(continuation(w).path("strategy").asText());
    }
    private JsonNode motionEditPayload(StyledAssetStore.Work w) {
        int seed=(int)(((long)w.traits().path("seed").asInt()+7919L*w.repairCount())%2147483647);
        if(idleEdit(w) && (StyledSpriteCodec.SEED_IDLE_VERSION.equals(w.qualityPolicy().path("seedIdleRepair").asText())
            || StyledSpriteCodec.SEED_IDLE_VERSION.equals(continuation(w).path("strategy").asText())))return codec.seedIdle(w.direction(),seed(w),seed);
        var previous=store.previousAttempt(w);byte[] sheet=storage.asset(previous.path("key").asText());
        if(!StyledSpriteCodec.sha(sheet).equals(previous.path("sha256").asText()))throw new AssetException(409,"STYLED_SHEET_CHANGED");
        if(idleEdit(w))return codec.idleEdit(w.direction(),sheet,seed);
        if(tailEdit(w))return codec.tailEdit(w.direction(),sheet,seed);
        return codec.marginEdit(w.action(),w.direction(),sheet,seed);
    }
    private byte[] seed(StyledAssetStore.Work w) {
        var base=store.seed(w);byte[] seed=storage.asset(base.path("keys").path(w.direction()).asText());
        if(!StyledSpriteCodec.sha(seed).equals(base.path("hashes").path(w.direction()).asText()))throw new AssetException(409,"STYLED_SEED_CHANGED");
        return seed;
    }
}
