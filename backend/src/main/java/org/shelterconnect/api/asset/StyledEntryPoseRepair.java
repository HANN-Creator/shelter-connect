package org.shelterconnect.api.asset;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** A broken SIT entry must restart from the approved standing image, not edit a seated strip. */
final class StyledEntryPoseRepair {
    static final String VERSION="standing-seated-endpoints-restart-v1";
    private StyledEntryPoseRepair() {}
    static JsonNode plan(JsonNode report,String action,JsonMapper json) {
        if(!action.equals("SIT") || report==null || report.path("passed").asBoolean())return null;
        var layers=new ArrayList<JsonNode>();layers.add(report);
        for(String key:List.of("rawEditReview","restoredReview"))if(report.has(key))layers.add(report.path(key));
        try {
            for(var r:layers) {
                if(!StyledMotionReview.VERSION.equals(r.path("motionReviewVersion").asText())
                    || !StyledSpriteCodec.qualityRulesSha().equals(r.path("rulesSha256").asText())
                    || r.path("passed").asBoolean() || r.path("referencePoseUsable").asBoolean()
                    || r.path("observationCount").asInt()!=2
                    || !r.path("confirmedProperties").valueStream().anyMatch(p->p.asText().equals("referencePose"))
                    || !r.path("issues").valueStream().anyMatch(p->p.asText().equals("DISCONTINUITY")))return null;
                for(String key:List.of("initialVision","consistencyReview")) {
                    var o=r.path(key);StyledMotionReview.validate(o,action);
                    var p=StyledMotionReview.property(o,"referencePose");
                    if(!p.path("state").asText().equals("FAIL") || !p.path("frames").valueStream().anyMatch(f->f.asInt()==0))return null;
                    // Restarting a pose cannot adjudicate disputed identity or invent missing anatomy.
                    for(String name:StyledMotionReview.PROPERTIES.keySet())
                        if(!Set.of("referencePose","action").contains(name)
                            && !StyledMotionReview.property(o,name).path("state").asText().equals("PASS"))return null;
                }
                for(String key:List.of("edgeFrames","silhouetteFrames","detachedFrames","idleMotionFrames"))
                    if(!r.path(key).isArray() || !r.path(key).isEmpty())return null;
            }
        }catch(AssetException invalid){return null;}
        var p=json.createObjectNode().put("version",VERSION).put("assessment","CONFIRMED_ENTRY_RESTART");
        p.putArray("properties").add("referencePose");p.putArray("issues").add("DISCONTINUITY");p.putArray("frames").add(0);
        p.put("note","Restart SIT from the exact approved standing first_frame toward the existing seated last_frame. These are generation constraints, not approvals. Show hind legs folding and rump lowering, then hold seated. Review all nine new frames.");
        return p;
    }
    static JsonNode endpoints(JsonNode payload,List<byte[]> previous,JsonMapper json) {
        if(previous.size()!=9 || StyledSpriteCodec.motionFrame(previous.getLast()).getWidth()!=40)
            throw new AssetException(409,"RECOVERY_INPUT_CHANGED");
        var out=(tools.jackson.databind.node.ObjectNode)payload.deepCopy();
        out.set("last_frame",json.valueToTree(Map.of("type","base64","base64",Base64.getEncoder().encodeToString(previous.getLast()))));
        String base=out.path("description").asText();
        // Rear-view captions are longer. Avoid consuming the lesson composer's remaining text budget.
        String description=base+(base.length()>800?" End seated like last_frame.":
            " Transition from standing first_frame to seated last_frame; settle and hold the final seated pose.");
        if(description.length()>1000)throw new AssetException(422,"RECOVERY_PROMPT_LIMIT");
        out.put("description",description);return out;
    }
}
