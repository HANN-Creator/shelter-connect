package org.shelterconnect.api.asset;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

@Service
public class AssetManifestService {
    private final AssetStore store;
    private final AssetStorage storage;
    public AssetManifestService(AssetStore store,AssetStorage storage) { this.store=store;this.storage=storage; }
    public Map<String,Object> preview(UUID subject,UUID dog,UUID id) {
        store.checkPreview(subject,dog,id);
        var result=manifest(store.read(subject,dog,id));
        store.checkPreview(subject,dog,id);return result;
    }
    public Map<String,Object> published(UUID dog) {
        var before=store.available(dog);var result=manifest(before);
        if(!store.available(dog).id().equals(before.id())) throw new AssetException(409,"ASSET_CHANGED");
        return result;
    }
    private Map<String,Object> manifest(AssetStore.Job job) {
        var steps=job.steps();
        if(!AssetStore.stepsComplete(job) || steps.stream().anyMatch(s->s.result()==null || !s.status().equals("SUCCEEDED"))) throw new AssetException(409,"ASSET_NOT_READY");
        var keys=steps.stream().map(s->s.result().path("key").asText()).toList();
        var links=storage.sign(keys);
        var clips=new LinkedHashMap<String,Object>();
        String base=null;
        for(var step:steps) {
            JsonNode m=step.result();String link=links.get(m.path("key").asText());
            if(link==null) throw AssetException.unavailable();
            if(step.action().equals("BASE")) { base=link;continue; }
            int count=m.path("frameCount").asInt(),duration=m.path("durationMs").asInt();
            var frames=new ArrayList<Map<String,Integer>>();
            for(int i=0;i<count;i++) frames.add(Map.of("x",i*64,"y",0,"width",64,"height",64,"durationMs",duration));
            clips.put(step.action(),Map.of("spritesheetUrl",link,"frameCount",count,"loop",m.path("loop").asBoolean(),
                "holdLastFrame",m.path("holdLastFrame").asBoolean(),"returnToIdle",m.path("returnToIdle").asText(),"frames",frames));
        }
        var result=new LinkedHashMap<String,Object>(Map.of("schemaVersion",1,"id",job.id(),"status",job.status(),"provider","PixelLab + motion harness",
            "frameSize",Map.of("width",64,"height",64),"anchorPixels",Map.of("x",32,"y",60),
            "facing","right-three-quarter","baseUrl",Objects.requireNonNull(base),"expiresAt",Instant.now().plusSeconds(60),"animations",clips));
        result.put("availableActions",job.actionPlan().stream().filter(a->!a.equals("BASE")).toList());
        result.put("behaviorRevision",job.behaviorRevision());result.put("fallbackAction","IDLE");
        return result;
    }
}
