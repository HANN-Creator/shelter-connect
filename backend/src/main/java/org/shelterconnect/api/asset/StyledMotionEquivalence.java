package org.shelterconnect.api.asset;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Reuses tail-shape evidence only across a proven color-only entry-frame restoration. */
final class StyledMotionEquivalence {
    static final String VERSION="front-sit-tail-geometry-v1";
    private StyledMotionEquivalence() {}
    static boolean observations(JsonNode raw,JsonNode restored,String action,String direction) {
        if(!action.equals("SIT") || !direction.equals("south") || !StyledMotionReview.VERSION.equals(raw.path("motionReviewVersion").asText())
            || !StyledMotionReview.VERSION.equals(restored.path("motionReviewVersion").asText())
            || !raw.path("motionDecision").asText().equals("UNCERTAIN") || raw.path("passed").asBoolean()
            || !restored.path("motionDecision").asText().equals("PASS") || !restored.path("passed").asBoolean()
            || !raw.path("uncertainProperties").toString().equals("[\"tail\"]") || raw.path("observationCount").asInt()!=2
            || !raw.path("referenceFrameSha256").asText().matches("[a-f0-9]{64}")
            || !raw.path("referenceFrameSha256").equals(restored.path("referenceFrameSha256"))
            || !raw.path("rulesSha256").equals(restored.path("rulesSha256")))return false;
        for(var r:List.of(raw,restored))for(String field:List.of("issues","confirmedProperties","edgeFrames","silhouetteFrames","detachedFrames","idleMotionFrames"))
            if(!r.path(field).isArray() || !r.path(field).isEmpty())return false;
        try {
            var a=raw.path("initialVision");var b=raw.path("consistencyReview");
            var states=new HashSet<String>(List.of(StyledMotionReview.property(a,"tail").path("state").asText(),StyledMotionReview.property(b,"tail").path("state").asText()));
            if(!states.equals(Set.of("PASS","UNCERTAIN")))return false;
            for(var observation:List.of(a,b))for(String key:StyledMotionReview.PROPERTIES.keySet())
                if(!key.equals("tail") && !StyledMotionReview.property(observation,key).path("state").asText().equals("PASS"))return false;
        }catch(AssetException invalid){return false;}
        return true;
    }
    static JsonNode reconcile(JsonNode raw,JsonNode restored,List<byte[]> rawFrames,List<byte[]> frames,String action,String direction,JsonMapper json) {
        if(!observations(raw,restored,action,direction) || rawFrames.size()!=9 || frames.size()!=9)return raw;
        var rawHashes=json.valueToTree(rawFrames.stream().map(StyledSpriteCodec::sha).toList());var hashes=json.valueToTree(frames.stream().map(StyledSpriteCodec::sha).toList());
        if(!rawHashes.equals(raw.path("reviewedFrameHashes")) || !hashes.equals(restored.path("reviewedFrameHashes")))return raw;
        for(int i=0;i<9;i++) {
            var a=StyledSpriteCodec.motionFrame(rawFrames.get(i));var b=StyledSpriteCodec.motionFrame(frames.get(i));
            if(a.getWidth()!=40 || b.getWidth()!=40)return raw;
            for(int y=0;y<40;y++)for(int x=0;x<40;x++) {
                int p=a.getRGB(x,y),q=b.getRGB(x,y);
                if((p>>>24)!=(q>>>24) || dark(p)!=dark(q) || (i>0 && p!=q))return raw;
            }
        }
        var result=(ObjectNode)raw.deepCopy();result.set("occlusionOriginalReport",raw.deepCopy());
        result.put("passed",true).put("motionDecision","PASS").put("note","정면 가림 관찰만 불확실: 같은 윤곽·동일한 1..8프레임과 통과 보정본의 꼬리 형태 근거를 결합; 원래 관찰은 학습하지 않음");
        result.putArray("uncertainProperties");var proof=result.putObject("tailGeometryReconciliation").put("version",VERSION);
        proof.set("rawFrameHashes",rawHashes);proof.set("restoredFrameHashes",hashes);return result;
    }
    static boolean bound(JsonNode raw,JsonNode restored,String action,String direction) {
        if(!raw.has("tailGeometryReconciliation"))return true;
        var proof=raw.path("tailGeometryReconciliation");return VERSION.equals(proof.path("version").asText())
            && observations(raw.path("occlusionOriginalReport"),restored,action,direction)
            && proof.path("rawFrameHashes").size()==9 && proof.path("restoredFrameHashes").size()==9
            && proof.path("rawFrameHashes").equals(raw.path("reviewedFrameHashes"))
            && proof.path("rawFrameHashes").equals(raw.path("occlusionOriginalReport").path("reviewedFrameHashes"))
            && proof.path("restoredFrameHashes").equals(restored.path("reviewedFrameHashes"));
    }
    private static boolean dark(int p){return (p>>>24)!=0 && (p>>16&255)<100 && (p>>8&255)<100 && (p&255)<100;}
}
