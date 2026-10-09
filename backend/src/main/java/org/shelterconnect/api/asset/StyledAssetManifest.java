package org.shelterconnect.api.asset;

import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import org.shelterconnect.api.behavior.*;

@Service
public class StyledAssetManifest {
    private final StyledAssetStore store;private final AssetStorage storage;private final BehaviorService behaviors;
    public StyledAssetManifest(StyledAssetStore store,AssetStorage storage,BehaviorService behaviors) { this.store=store;this.storage=storage;this.behaviors=behaviors; }
    public boolean handles(UUID id) { return store.isStyled(id); }
    public Map<String,Object> published(UUID id) {
        var job=store.forManifest(id);var result=manifest(job,false);
        // Generation is a historical snapshot; revoked evidence must not keep old interactions alive.
        if(job.generationPlan()!=null) {
            var metadata=new LinkedHashMap<String,Object>();
            for(String key:List.of("policyVersion","basis","behaviorRevision","baseActions","optionalActions","selectedActions","directionCount","expectedProviderRequests"))
                metadata.put(key,job.generationPlan().path(key));
            result.put("generationPlan",metadata);
        }
        var current=behaviors.playback(job.dogId().toString());
        result.put("behavior",new BehaviorTypes.Playback(current.dogId(),current.schemaVersion(),current.basis(),current.revision(),
            BehaviorGenerationPlan.compatible(current.settings(),job.availableActions()),
            BehaviorInteractions.forAvailable(current.settings(),job.availableActions())));
        return result;
    }
    public Map<String,Object> preview(UUID subject,UUID dog,UUID id) {
        var j=store.preview(subject,dog,id);var result=manifest(j,j.status().equals("SEED_REVIEW") || (j.qualityPolicy()!=null && j.qualityPolicy().path("referenceOnly").asBoolean()));
        if(!store.preview(subject,dog,id).status().equals(j.status()))throw new AssetException(409,"ASSET_CHANGED");return result;
    }
    private Map<String,Object> manifest(StyledAssetStore.Job job,boolean seedsOnly) {
        if(job.steps().isEmpty() || (!seedsOnly && !job.complete()))
            throw new AssetException(409,"ASSET_NOT_READY");
        var base=job.steps().getFirst().result();if(base==null)throw new AssetException(409,"ASSET_NOT_READY");
        var keys=new ArrayList<String>();StyledSpriteCodec.DIRECTIONS.forEach(d->keys.add(base.path("keys").path(d).asText()));
        if(!seedsOnly)job.steps().stream().skip(1).forEach(s->keys.add(s.result().path("key").asText()));
        var urls=storage.sign(keys);
        Map<String,String> directions=new LinkedHashMap<>();StyledSpriteCodec.DIRECTIONS.forEach(d->directions.put(d,url(urls,base.path("keys").path(d).asText())));
        var result=new LinkedHashMap<String,Object>(Map.of("schemaVersion",1,"id",job.id(),"status",job.status(),"provider","PixelLab Pro + PixMiniMax",
            "generatorVersion",StyledSpriteCodec.VERSION,"frameSize",Map.of("width",32,"height",32),"anchorPixels",Map.of("x",16,"y",30),
            "expiresAt",Instant.now().plusSeconds(60),"baseUrl",directions.get("south"),"sampling","nearest"));
        int motionSize=StyledRecovery.size(job.qualityPolicy());
        if(motionSize==40 && !seedsOnly){result.put("frameSize",Map.of("width",40,"height",40));result.put("anchorPixels",Map.of("x",20,"y",34));
            result.put("baseFrameSize",Map.of("width",32,"height",32));result.put("baseAnchorPixels",Map.of("x",16,"y",30));
            result.put("contentPaddingPixels",4);result.put("nativeContentSize",32);}
        if(job.generationPlan()!=null)result.put("generationPlan",job.generationPlan());
        result.put("availableActions",job.availableActions());
        if(job.qualityPolicy()!=null && job.qualityPolicy().path("referenceOnly").asBoolean()) {
            result.put("referenceOnly",true);result.put("provider","Imported seed reference");
        }
        if(job.qualityPolicy()!=null)result.put("quality",Map.of("policyVersion",job.qualityPolicy().path("version"),
            "passed",job.complete() && job.steps().stream().skip(1).allMatch(s->s.qualityReport()!=null && s.qualityReport().path("passed").asBoolean()),
            "repairCount",job.steps().stream().mapToInt(StyledAssetStore.Step::repairCount).sum()));
        if(seedsOnly) { result.put("directions",directions);result.put("seedHashes",base.path("hashes"));return result; }
        var clips=new LinkedHashMap<String,Map<String,Object>>();
        var names=Map.of("south","DOWN","north","UP","west","LEFT","east","RIGHT");
        for(String direction:names.values())clips.put(direction,new LinkedHashMap<>());
        for(var step:job.steps().subList(1,job.steps().size())) {
            JsonNode m=step.result();int duration=m.path("durationMs").asInt();boolean loop=m.path("loop").asBoolean();
            var frames=new ArrayList<Map<String,Integer>>();for(int i=0;i<9;i++)frames.add(Map.of("x",i*motionSize,"y",0,"width",motionSize,"height",motionSize,"durationMs",duration));
            var unit=switch(step.direction()) { case "south"->new int[]{0,1};case "north"->new int[]{0,-1};case "west"->new int[]{-1,0};default->new int[]{1,0}; };
            boolean moving=Set.of("WALK","RUN","BACK_OFF").contains(step.action());int sign=step.action().equals("BACK_OFF")?-1:1;
            clips.get(names.get(step.direction())).put(step.action(),Map.of("spritesheetUrl",url(urls,m.path("key").asText()),"frameCount",9,"frames",frames,
                "loop",loop,"holdLastFrame",!loop,"returnToIdle",loop?"DIRECT":"REVERSE_FRAMES","sha256",m.path("sha256").asText(),
                "worldMotion",Map.of("unitVector",Map.of("x",moving?sign*unit[0]:0,"y",moving?sign*unit[1]:0),"speedControlledByFrontend",true)));
        }
        result.put("mapDirections",clips);result.put("animations",clips.get("RIGHT"));
        result.put("fallbackAction","IDLE");result.put("facing","four-directions");result.put("variants",Map.of());return result;
    }
    private String url(Map<String,String> urls,String key) { var url=urls.get(key);if(url==null)throw AssetException.unavailable();return url; }
}
