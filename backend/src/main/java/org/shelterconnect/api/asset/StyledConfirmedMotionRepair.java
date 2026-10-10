package org.shelterconnect.api.asset;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** An edit target, not a quality verdict. Unrelated uncertainty still blocks approval and learning. */
final class StyledConfirmedMotionRepair {
    static final String VERSION="confirmed-motion-repair-v1";
    private StyledConfirmedMotionRepair() {}

    static JsonNode plan(JsonNode report,String action,JsonMapper json) {
        var entry=StyledEntryPoseRepair.plan(report,action,json);if(entry!=null)return entry;
        if(report==null || report.path("passed").asBoolean() || !StyledMotionReview.unresolved(report))return null;
        // Retain the previously verified narrow recipes, including their historical replay fixtures.
        if(StyledMotionReview.confirmedTailRepair(report))return legacy(report,json,
            "Both observations confirm a tail defect at shared frames. Identity disagreement refers only to those frames; repair that tail defect, preserving all other anatomy.");
        if(StyledMotionReview.confirmedPaletteRepair(report))return legacy(report,json,
            "Both observations confirm conspicuous coat/feature color flicker. Restore stable approved coat colors and remove introduced contrasting head pixels. Preserve anatomy, facing and the sitting motion; do not redesign uncertain features.");

        var reports=new ArrayList<JsonNode>();reports.add(report);
        for(String key:List.of("rawEditReview","restoredReview"))if(report.has(key))reports.add(report.path(key));
        // A combined original/restored report must agree on an edit target in BOTH images.
        // Do not choose whichever observation happens to permit another paid attempt.
        Map<String,Set<Integer>> targets=null;
        try {
            for(var r:reports) {
                if(!StyledMotionReview.VERSION.equals(r.path("motionReviewVersion").asText())
                    || !StyledSpriteCodec.qualityRulesSha().equals(r.path("rulesSha256").asText())
                    || r.path("passed").asBoolean() || r.path("observationCount").asInt()!=2
                    || !r.path("confirmedProperties").isArray() || !r.path("issues").isArray())return null;
                var a=r.path("initialVision");var b=r.path("consistencyReview");
                StyledMotionReview.validate(a,action);StyledMotionReview.validate(b,action);
                // This continuation preserves the usable entry pose. A disputed entry pose needs its own repair.
                if(!r.path("referencePoseUsable").asBoolean()
                    || !StyledMotionReview.property(a,"referencePose").path("state").asText().equals("PASS")
                    || !StyledMotionReview.property(b,"referencePose").path("state").asText().equals("PASS"))return null;
                var current=new TreeMap<String,Set<Integer>>();
                for(String property:StyledMotionReview.PROPERTIES.keySet()) {
                    if(r.path("confirmedProperties").valueStream().noneMatch(n->n.asText().equals(property)))continue;
                    if(r.path("issues").valueStream().noneMatch(n->n.asText().equals(StyledMotionReview.PROPERTIES.get(property))))continue;
                    var pa=StyledMotionReview.property(a,property);var pb=StyledMotionReview.property(b,property);
                    if(!pa.path("state").asText().equals("FAIL") || !pb.path("state").asText().equals("FAIL"))continue;
                    var shared=frames(pa);shared.retainAll(frames(pb));
                    if(!shared.isEmpty())current.put(property,shared);
                }
                if(targets==null)targets=current;
                else {
                    targets.keySet().retainAll(current.keySet());
                    for(var e:targets.entrySet())e.getValue().retainAll(current.get(e.getKey()));
                    targets.values().removeIf(Set::isEmpty);
                }
                if(targets.isEmpty())return null;
            }
        }catch(AssetException invalid){return null;}
        if(targets==null || targets.isEmpty())return null;
        var issues=new TreeSet<String>();var frames=new TreeSet<Integer>();
        targets.forEach((p,fs)->{issues.add(StyledMotionReview.PROPERTIES.get(p));frames.addAll(fs);});
        var plan=json.createObjectNode().put("version",VERSION).put("assessment","CONFIRMED_TARGET_ONLY");
        plan.set("properties",json.valueToTree(targets.keySet()));plan.set("propertyFrames",json.valueToTree(targets));
        plan.set("issues",json.valueToTree(issues));plan.set("frames",json.valueToTree(frames));
        plan.put("note","Both observations confirm these targets at shared frames. Repair only these defects; preserve the approved anatomy, facing, entry pose and style. Other disputed properties remain unconfirmed; do not invent hidden features. Fresh full review is required.");
        return plan;
    }
    private static TreeSet<Integer> frames(JsonNode property) {
        var out=new TreeSet<Integer>();property.path("frames").forEach(f->out.add(f.asInt()));return out;
    }
    private static JsonNode legacy(JsonNode report,JsonMapper json,String note) {
        var p=json.createObjectNode().put("version",VERSION).put("assessment","CONFIRMED_TARGET_ONLY").put("note",note);
        p.set("issues",report.path("issues").deepCopy());p.set("frames",report.path("frames").deepCopy());return p;
    }
}
