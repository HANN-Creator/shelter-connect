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
    public StyledAssetWorker(StyledAssetStore store,StyledAssetProvider provider,AssetStorage storage,StyledSpriteCodec codec,AssetProperties properties,JsonMapper json,StyledQualityAgent quality,StyledLessonStore lessons,StyledSeedQualityAgent seedQuality) {
        this.store=store;this.provider=provider;this.storage=storage;this.codec=codec;this.properties=properties;this.json=json;this.quality=quality;this.lessons=lessons;this.seedQuality=seedQuality;
    }
    public void tick() {
        if(!properties.enabled)return;
        var w=store.claim();if(w==null)return;boolean submitted=false;
        try {
            if(!store.authorized(w))return;
            if(w.qualityPolicy()!=null && w.qualityPolicy().has("rulesSha256")
                && !StyledSpriteCodec.qualityRulesSha().equals(w.qualityPolicy().path("rulesSha256").asText()))
                throw new AssetException(409,"QUALITY_RULES_CHANGED");
            if(!w.character() && w.qualityPolicy()!=null && !w.qualityPolicy().has("contract")) {
                if(!store.startContract(w))return;
                var contract=quality.contract(storage.photo(w.dogId(),w.bucket(),w.key()),w.traits());
                store.contract(w,contract);store.defer(w,0);return;
            }
            if(w.status().equals("CHECKING")) {
                byte[] sheet=storage.asset(w.result().path("key").asText());
                if(!StyledSpriteCodec.sha(sheet).equals(w.result().path("sha256").asText()))throw new AssetException(409,"STYLED_SHEET_CHANGED");
                var frames=StyledSpriteCodec.frames(sheet);StyledSpriteCodec.sheet(frames,seed(w));
                inspect(w,frames,w.result());return;
            }
            if(w.status().equals("PENDING")) {
                var policy=w.qualityPolicy()==null?json.createObjectNode():
                        json.valueToTree(Map.of("contract",w.qualityPolicy().path("contract"),"attempt",w.repairCount(),
                            "rulesSha256",w.qualityPolicy().path("rulesSha256").asText(),
                            "issues",w.qualityReport()!=null && w.qualityReport().path("issues").isArray()?w.qualityReport().path("issues"):json.createArrayNode()));
                JsonNode payload=tailEdit(w)?tailEditPayload(w):w.character()?codec.character(w.dogId(),w.traits(),storage.photo(w.dogId(),w.bucket(),w.key()),policy):
                    codec.motion(w.traits(),w.action(),w.direction(),seed(w),policy);
                if(!w.character()) {
                    var selected=lessons.pin(w,(tailEdit(w)?2000:1000)-payload.path("description").asText().length());
                    if(!selected.isEmpty()) {
                        ((tools.jackson.databind.node.ObjectNode)policy).set("lessons",selected);
                        if(tailEdit(w)) {
                            String suffix=" Lessons: "+String.join(" ",selected.valueStream().map(n->n.path("prevention").asText()).toList());
                            ((tools.jackson.databind.node.ObjectNode)payload).put("description",payload.path("description").asText()+suffix);
                        } else payload=codec.motion(w.traits(),w.action(),w.direction(),seed(w),policy);
                    }
                }
                if(!store.reserve(w,payload))return;
                submitted=true;
                UUID id=tailEdit(w)?provider.editAnimation(payload):provider.submit(w.character(),payload);store.accepted(w,id);return;
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
                    var report=w.qualityReport();
                    if(report==null || !StyledSeedQualityAgent.VERSION.equals(report.path("version").asText())
                        || !StyledSeedQualityAgent.binding(seeds).equals(report.path("inputSha256").asText())
                        || !StyledSpriteCodec.qualityRulesSha().equals(report.path("rulesSha256").asText())) {
                        if(!store.startQuality(w))return;
                        report=seedQuality.review(storage.photo(w.dogId(),w.bucket(),w.key()),seeds);store.quality(w,report);
                    }
                    if(store.retryQuality(w,report,metadata))return;
                }
                store.success(w,metadata);
            } else {
                var frames=result.path("frames").valueStream().map(n->StyledPixelLabClient.decode(n.asText())).toList();
                JsonNode rawEdit=null;
                if(tailEdit(w)) {
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
                if(result.has("usage"))((tools.jackson.databind.node.ObjectNode)metadata).set("providerUsage",result.path("usage"));
                inspect(w,frames,metadata);
            }
        } catch(AssetProvider.Failure e) { store.fail(w,e.uncertain,e.code); }
        catch(AssetException e) { store.fail(w,false,e.code); }
        catch(RuntimeException e) { store.fail(w,submitted,"STYLED_WORK_INTERRUPTED"); }
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
                var combined=(tools.jackson.databind.node.ObjectNode)review;
                combined.set("issues",json.valueToTree(issues));combined.put("passed",review.path("passed").asBoolean() && rawReview.path("passed").asBoolean());
                combined.set("rawEditReview",rawReview);combined.put("rawEditSha256",source.path("sha256").asText());
            }
            var details=(tools.jackson.databind.node.ObjectNode)review;details.put("inputSha256",sha);details.put("lessonsSha256",lessonSha);details.set("learnedLessons",selected);
            report=review;store.quality(w,report);
        }
        // A failed raw edit must teach from that raw image, never a clean restored image with the wrong label.
        if(metadata.has("rawEdit") && !report.path("rawEditReview").path("passed").asBoolean())
            lessons.record(w,report.path("rawEditReview"),metadata.path("rawEdit"),store.seed(w));
        else lessons.record(w,report,metadata,store.seed(w));
        if(!store.retryQuality(w,report,metadata))store.success(w,metadata);
    }
    static boolean tailEdit(StyledAssetStore.Work w) {
        return w.action().equals("TAIL_WAG") && w.repairCount()==2 && w.qualityPolicy()!=null
            && w.qualityPolicy().path("lowTailRepair").asText().equals("REGENERATE_THEN_EDIT_ONCE")
            && w.qualityPolicy().at("/contract/tailCarriage").asText().equals("LOW");
    }
    private JsonNode tailEditPayload(StyledAssetStore.Work w) {
        var previous=store.previousAttempt(w);byte[] sheet=storage.asset(previous.path("key").asText());
        if(!StyledSpriteCodec.sha(sheet).equals(previous.path("sha256").asText()))throw new AssetException(409,"STYLED_SHEET_CHANGED");
        int seed=(int)(((long)w.traits().path("seed").asInt()+7919L*w.repairCount())%2147483647);
        return codec.tailEdit(w.direction(),sheet,seed);
    }
    private byte[] seed(StyledAssetStore.Work w) {
        var base=store.seed(w);byte[] seed=storage.asset(base.path("keys").path(w.direction()).asText());
        if(!StyledSpriteCodec.sha(seed).equals(base.path("hashes").path(w.direction()).asText()))throw new AssetException(409,"STYLED_SEED_CHANGED");
        return seed;
    }
}
