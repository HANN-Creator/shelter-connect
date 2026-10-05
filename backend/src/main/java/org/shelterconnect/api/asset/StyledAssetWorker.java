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
    public StyledAssetWorker(StyledAssetStore store,StyledAssetProvider provider,AssetStorage storage,StyledSpriteCodec codec,AssetProperties properties,JsonMapper json,StyledQualityAgent quality) {
        this.store=store;this.provider=provider;this.storage=storage;this.codec=codec;this.properties=properties;this.json=json;this.quality=quality;
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
                JsonNode payload=w.character()?codec.character(w.dogId(),w.traits(),storage.photo(w.dogId(),w.bucket(),w.key())):
                    codec.motion(w.traits(),w.action(),w.direction(),seed(w),w.qualityPolicy()==null?json.createObjectNode():
                        json.valueToTree(Map.of("contract",w.qualityPolicy().path("contract"),"attempt",w.repairCount(),
                            "rulesSha256",w.qualityPolicy().path("rulesSha256").asText(),
                            "issues",w.qualityReport()!=null && w.qualityReport().path("issues").isArray()?w.qualityReport().path("issues"):json.createArrayNode())));
                if(!store.reserve(w,payload))return;
                submitted=true;
                UUID id=provider.submit(w.character(),payload);store.accepted(w,id);return;
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
                for(String d:StyledSpriteCodec.DIRECTIONS) {
                    byte[] image=StyledPixelLabClient.decode(result.path("directions").path(d).asText());
                    String key=w.prefix()+"directions/"+d+".png";storage.put(key,image);keys.put(d,key);hashes.put(d,StyledSpriteCodec.sha(image));
                }
                store.success(w,json.valueToTree(Map.of("keys",keys,"hashes",hashes)));
            } else {
                var frames=result.path("frames").valueStream().map(n->StyledPixelLabClient.decode(n.asText())).toList();
                byte[] sheet=StyledSpriteCodec.sheet(frames,seed(w));
                String key=w.prefix()+"sheets/"+(w.repairCount()==0?"":"repair-"+w.repairCount()+"/")+w.label()+".png";storage.put(key,sheet);
                var spec=StyledSpriteCodec.rules(json).path("actions").path(w.action());
                var metadata=json.valueToTree(Map.of("key",key,"sha256",StyledSpriteCodec.sha(sheet),"frameCount",9,
                    "durationMs",spec.path("durationMs").asInt(),"loop",spec.path("loop").asBoolean()));
                inspect(w,frames,metadata);
            }
        } catch(AssetProvider.Failure e) { store.fail(w,e.uncertain,e.code); }
        catch(AssetException e) { store.fail(w,false,e.code); }
        catch(RuntimeException e) { store.fail(w,submitted,"STYLED_WORK_INTERRUPTED"); }
    }
    private void inspect(StyledAssetStore.Work w,List<byte[]> frames,JsonNode metadata) {
        if(w.qualityPolicy()==null){store.success(w,metadata);return;}
        var report=w.qualityReport();String sha=metadata.path("sha256").asText();
        if(report==null || !sha.equals(report.path("inputSha256").asText())
            || !StyledSpriteCodec.qualityRulesSha().equals(report.path("rulesSha256").asText())) {
            if(!store.startQuality(w))return;
            var base=store.seed(w);var seeds=new ArrayList<byte[]>();
            for(String d:StyledSpriteCodec.DIRECTIONS) {
                var seed=storage.asset(base.path("keys").path(d).asText());
                if(!StyledSpriteCodec.sha(seed).equals(base.path("hashes").path(d).asText()))throw new AssetException(409,"STYLED_SEED_CHANGED");
                seeds.add(seed);
            }
            var review=quality.review(w.qualityPolicy().path("contract"),seeds,frames,w.action(),w.direction()).deepCopy();
            ((tools.jackson.databind.node.ObjectNode)review).put("inputSha256",sha);report=review;store.quality(w,report);
        }
        if(!store.retryQuality(w,report,metadata))store.success(w,metadata);
    }
    private byte[] seed(StyledAssetStore.Work w) {
        var base=store.seed(w);byte[] seed=storage.asset(base.path("keys").path(w.direction()).asText());
        if(!StyledSpriteCodec.sha(seed).equals(base.path("hashes").path(w.direction()).asText()))throw new AssetException(409,"STYLED_SEED_CHANGED");
        return seed;
    }
}
