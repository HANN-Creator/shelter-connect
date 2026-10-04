package org.shelterconnect.api.asset;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class StyledAssetWorker {
    private final StyledAssetStore store;private final StyledAssetProvider provider;private final AssetStorage storage;
    private final StyledSpriteCodec codec;private final AssetProperties properties;private final JsonMapper json;
    public StyledAssetWorker(StyledAssetStore store,StyledAssetProvider provider,AssetStorage storage,StyledSpriteCodec codec,AssetProperties properties,JsonMapper json) {
        this.store=store;this.provider=provider;this.storage=storage;this.codec=codec;this.properties=properties;this.json=json;
    }
    public void tick() {
        if(!properties.enabled)return;
        var w=store.claim();if(w==null)return;boolean submitted=false;
        try {
            if(!store.authorized(w))return;
            if(w.status().equals("PENDING")) {
                JsonNode payload=w.character()?codec.character(w.dogId(),w.traits(),storage.photo(w.dogId(),w.bucket(),w.key())):
                    codec.motion(w.traits(),w.action(),w.direction(),seed(w));
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
                String key=w.prefix()+"sheets/"+w.label()+".png";storage.put(key,sheet);
                var spec=StyledSpriteCodec.rules(json).path("actions").path(w.action());
                store.success(w,json.valueToTree(Map.of("key",key,"sha256",StyledSpriteCodec.sha(sheet),"frameCount",9,
                    "durationMs",spec.path("durationMs").asInt(),"loop",spec.path("loop").asBoolean())));
            }
        } catch(AssetProvider.Failure e) { store.fail(w,e.uncertain,e.code); }
        catch(AssetException e) { store.fail(w,false,e.code); }
        catch(RuntimeException e) { store.fail(w,submitted,"STYLED_WORK_INTERRUPTED"); }
    }
    private byte[] seed(StyledAssetStore.Work w) {
        var base=store.seed(w);byte[] seed=storage.asset(base.path("keys").path(w.direction()).asText());
        if(!StyledSpriteCodec.sha(seed).equals(base.path("hashes").path(w.direction()).asText()))throw new AssetException(409,"STYLED_SEED_CHANGED");
        return seed;
    }
}
