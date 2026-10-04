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
        var keys=new ArrayList<>(steps.stream().map(s->s.result().path("key").asText()).toList());
        boolean hasMap=mapComplete(steps);
        if(hasMap) for(var step:steps) keys.add(step.result().path("mapPixel").path("key").asText());
        var links=storage.sign(keys);
        var original=variant(job,links,false);
        var result=new LinkedHashMap<String,Object>(Map.of("schemaVersion",1,"id",job.id(),"status",job.status(),"provider","PixelLab + motion harness",
            "expiresAt",Instant.now().plusSeconds(60)));
        result.putAll(original);
        result.put("behaviorRevision",job.behaviorRevision());result.put("fallbackAction","IDLE");
        result.put("variants",hasMap?Map.of("MAP_32",variant(job,links,true)):Map.of());
        return result;
    }
    private static boolean mapComplete(List<AssetStore.Step> steps) {
        String palette=null,version=null;
        for(var step:steps) {
            var m=step.result().path("mapPixel");var v=m.path("validation");
            String hash=v.path("paletteSha256").asText();
            String currentVersion=v.path("converterVersion").asText();
            if(m.path("key").asText().isBlank() || m.path("width").asInt()!=32 || m.path("height").asInt()!=32
                || m.path("frameCount").asInt()!=step.result().path("frameCount").asInt()
                || !Set.of("map-pixel-v1","map-pixel-v2").contains(currentVersion) || !hash.matches("[a-f0-9]{64}")
                || !v.path("paletteChecked").asBoolean() || !v.path("boundsChecked").asBoolean() || !v.path("transparencyChecked").asBoolean()
                || (palette!=null && !palette.equals(hash)) || (version!=null && !version.equals(currentVersion))) return false;
            palette=hash;version=currentVersion;
        }
        return true;
    }
    private static Map<String,Object> variant(AssetStore.Job job,Map<String,String> links,boolean map) {
        int size=map?32:64;String base=null;
        var clips=new LinkedHashMap<String,Object>();
        for(var step:job.steps()) {
            JsonNode m=step.result(), image=map?m.path("mapPixel"):m;
            String link=links.get(image.path("key").asText());
            if(link==null) throw AssetException.unavailable();
            if(step.action().equals("BASE")) { base=link;continue; }
            int count=m.path("frameCount").asInt(),duration=m.path("durationMs").asInt();
            var frames=new ArrayList<Map<String,Integer>>();
            for(int i=0;i<count;i++) frames.add(Map.of("x",i*size,"y",0,"width",size,"height",size,"durationMs",duration));
            clips.put(step.action(),Map.of("spritesheetUrl",link,"frameCount",count,"loop",m.path("loop").asBoolean(),
                "holdLastFrame",m.path("holdLastFrame").asBoolean(),"returnToIdle",m.path("returnToIdle").asText(),"frames",frames));
        }
        var result=new LinkedHashMap<String,Object>(Map.of("frameSize",Map.of("width",size,"height",size),
            "anchorPixels",Map.of("x",size/2,"y",map?30:60),"facing","right-three-quarter",
            "baseUrl",Objects.requireNonNull(base),"animations",clips,
            "availableActions",job.actionPlan().stream().filter(a->!a.equals("BASE")).toList(),"fallbackAction","IDLE"));
        if(map) {
            result.put("generatorVersion",job.steps().getFirst().result().at("/mapPixel/validation/converterVersion").asText());
            result.put("sampling","nearest");
        }
        return result;
    }
}
