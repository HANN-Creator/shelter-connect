package org.shelterconnect.api.asset;

import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.*;
import java.util.List;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** One evidence refinement for front-view occlusion/loop doubt, never a majority-vote reroll. */
final class StyledWalkEvidence {
    static final String VERSION="walk-adjacent-occlusion-evidence-v1";
    private StyledWalkEvidence() {}
    static boolean eligible(JsonNode r,String action,String direction) {
        if(!action.equals("WALK") || !direction.equals("south") || r==null || r.has("walkEvidence")
            || !StyledMotionReview.VERSION.equals(r.path("motionReviewVersion").asText())
            || !StyledSpriteCodec.qualityRulesSha().equals(r.path("rulesSha256").asText())
            || !r.path("motionDecision").asText().equals("UNCERTAIN") || r.path("passed").asBoolean()
            || !r.path("referencePoseUsable").asBoolean() || r.path("observationCount").asInt()!=2
            || !r.path("uncertainProperties").isArray() || r.path("uncertainProperties").isEmpty()
            || r.path("uncertainProperties").valueStream().anyMatch(p->!Set.of("tail","loop").contains(p.asText())))return false;
        for(String key:List.of("issues","confirmedProperties","edgeFrames","silhouetteFrames","detachedFrames","idleMotionFrames"))
            if(!r.path(key).isArray() || !r.path(key).isEmpty())return false;
        try {
            var a=r.path("initialVision");var b=r.path("consistencyReview");
            StyledMotionReview.validate(a,action);StyledMotionReview.validate(b,action);
            for(String key:StyledMotionReview.PROPERTIES.keySet()) {
                var states=new HashSet<String>();states.add(StyledMotionReview.property(a,key).path("state").asText());states.add(StyledMotionReview.property(b,key).path("state").asText());
                if(!states.contains("PASS") || states.contains("FAIL") || (!Set.of("tail","loop").contains(key) && states.size()!=1))return false;
            }
        }catch(AssetException invalid){return false;}
        return true;
    }
    static ObjectNode refine(OpenAiResponsesClient client,AiProperties ai,JsonMapper json,ObjectNode r,
        List<byte[]> seeds,List<byte[]> frames,String action,String direction) {
        if(!eligible(r,action,direction))return r;
        var rules=StyledSpriteCodec.qualityRules(json);var images=StyledMotionReview.images(seeds,frames,direction,rules);
        images.putAll(pairs(frames));
        var e=json.createObjectNode().put("version",VERSION).put("rulesSha256",StyledSpriteCodec.qualityRulesSha())
            .put("model",ai.model()).put("reviewedAt",Instant.now().toString()).put("action",action).put("direction",direction);
        e.set("frameHashes",json.valueToTree(frames.stream().map(StyledSpriteCodec::sha).toList()));
        var hashes=e.putObject("imageHashes");images.forEach((k,v)->hashes.put(k,StyledSpriteCodec.sha(v)));
        JsonNode raw;
        try {raw=client.structuredImagesWithReasoning(rules.at("/recovery/walkEvidence/instruction").asText(),
            "Inspect a front-facing WALK. Full unchanged native40 frames and all nine adjacent transitions are shown, including 8 to 0. "
            +"All reference images are static identity context, never timeline frames. Record visible evidence for every frame and transition; no prior verdict is supplied.",images,schema(),"medium");}
        catch(AiFailure failure) {
            // This optional refinement cannot discard the two already valid observations or rerun them on timeout.
            e.put("status","UNAVAILABLE").put("failureCode",failure.code());r.set("walkEvidence",e);return r;
        }
        e.set("observation",raw);e.put("status",clear(raw)?"CLEAR":"UNRESOLVED");
        r.set("walkEvidence",e);
        if(clear(raw)) {
            r.set("beforeWalkEvidence",r.deepCopy().without("walkEvidence"));
            r.putArray("uncertainProperties");r.put("motionDecision","PASS").put("passed",true)
                .put("note","전체 원본 관찰과 인접 프레임·가림 근거를 함께 확인함; 원래 불확실 관찰 보존");
        }
        return r;
    }
    static boolean clear(JsonNode raw) {
        if(raw==null || !raw.path("frames").isArray() || raw.path("frames").size()!=9
            || !raw.path("transitions").isArray() || raw.path("transitions").size()!=9)return false;
        var frames=new HashSet<Integer>();var edges=new HashSet<Integer>();
        for(var f:raw.path("frames")) {
            if(!f.path("frame").isIntegralNumber() || f.path("frame").asInt()<0 || f.path("frame").asInt()>8 || !frames.add(f.path("frame").asInt())
                || !Set.of("VISIBLE_CONNECTED","OCCLUDED_BY_BODY").contains(f.path("tail").asText()) || !text(f.path("evidence")))return false;
        }
        for(var t:raw.path("transitions")) {
            int from=t.path("from").asInt(-1);
            if(!t.path("from").isIntegralNumber() || from<0 || from>8 || !edges.add(from)
                || !t.path("to").isIntegralNumber() || t.path("to").asInt(-1)!=(from+1)%9
                || !t.path("state").asText().equals("CONTINUOUS") || !text(t.path("evidence")))return false;
        }
        return true;
    }
    static boolean bound(JsonNode r) {
        if(!r.has("walkEvidence"))return true;
        var e=r.path("walkEvidence");var prior=r.path("beforeWalkEvidence");
        if(!r.path("passed").asBoolean())return true;
        return VERSION.equals(e.path("version").asText()) && e.path("status").asText().equals("CLEAR")
            && e.path("action").asText().equals("WALK") && e.path("direction").asText().equals("south")
            && e.path("rulesSha256").equals(r.path("rulesSha256")) && e.path("model").equals(r.path("model"))
            && e.path("frameHashes").equals(r.path("reviewedFrameHashes")) && e.path("imageHashes").size()==6
            && e.path("imageHashes").valueStream().allMatch(h->h.asText().matches("[a-f0-9]{64}"))
            && prior.path("reviewedFrameHashes").equals(r.path("reviewedFrameHashes"))
            && prior.path("referenceFrameSha256").equals(r.path("referenceFrameSha256"))
            && eligible(prior,"WALK","south") && clear(e.path("observation"));
    }
    private static boolean text(JsonNode v){return v.isTextual() && !v.asText().isBlank() && v.asText().length()<=300;}
    static Map<String,Object> schema() {
        var index=Map.of("type","integer","minimum",0,"maximum",8);
        var evidence=Map.of("type","string","minLength",1,"maxLength",300);
        return StyledQualityAgent.object(Map.of(
            "frames",Map.of("type","array","minItems",9,"maxItems",9,"items",StyledQualityAgent.object(Map.of(
                "frame",index,"tail",Map.of("type","string","enum",List.of("VISIBLE_CONNECTED","OCCLUDED_BY_BODY","VISIBLE_DEFECT","UNCERTAIN")),"evidence",evidence))),
            "transitions",Map.of("type","array","minItems",9,"maxItems",9,"items",StyledQualityAgent.object(Map.of(
                "from",index,"to",index,"state",Map.of("type","string","enum",List.of("CONTINUOUS","VISIBLE_JUMP","UNCERTAIN")),"evidence",evidence)))));
    }
    static Map<String,byte[]> pairs(List<byte[]> frames) {
        if(frames.size()!=9)throw new AssetException(422,"STYLED_FRAME_INVALID");
        var out=new LinkedHashMap<String,byte[]>();
        for(int board=0;board<3;board++) {
            var image=new BufferedImage(480,792,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();StyledRecoveryReview.paint(g,480,792);
            for(int row=0;row<3;row++) {
                int from=board*3+row,to=(from+1)%9,y=row*264;
                g.drawString("ACTUAL "+from+" -> "+to+(from==8?" LOOP SEAM":""),8,y+20);
                g.drawImage(StyledSpriteCodec.motionFrame(frames.get(from)),0,y+24,240,240,null);
                g.drawImage(StyledSpriteCodec.motionFrame(frames.get(to)),240,y+24,240,240,null);
            }g.dispose();out.put("ADJACENT chronological transitions "+(board*3)+" through "+(board*3+2),StyledSpriteCodec.png(image));
        }return out;
    }
}
