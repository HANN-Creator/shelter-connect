package org.shelterconnect.api.asset;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Explicit static rest alternative after the original paid budget is exhausted. */
final class StyledIdleHold {
    static final String VERSION="approved-seed-idle-hold-v1";
    private StyledIdleHold() {}
    static boolean derived(JsonNode result){return result!=null && VERSION.equals(result.at("/derivation/strategy").asText());}
    static boolean bound(JsonNode result,JsonNode seeds,String direction) {
        var d=result.path("derivation");var hashes=result.path("frameHashes");
        return derived(result) && d.path("motionKind").asText().equals("STATIC_IDLE")
            && d.path("seedHashes").equals(seeds) && d.path("direction").asText().equals(direction)
            && d.path("motionSeedSha256").equals(result.path("motionSeedSha256"))
            && d.path("sourceMotionSha256").asText().matches("[a-f0-9]{64}")
            && hashes.isArray() && hashes.size()==9 && hashes.valueStream().allMatch(h->h.equals(result.path("motionSeedSha256")));
    }
    static boolean eligible(StyledAssetStore.Work w,JsonNode report,JsonNode result) {
        return w.action().equals("IDLE") && StyledRecovery.enabled(w.qualityPolicy())
            && VERSION.equals(w.qualityPolicy().path("idleHoldVersion").asText())
            && StyledSpriteCodec.qualityRulesSha().equals(w.qualityPolicy().path("rulesSha256").asText())
            && Set.of("WAITING","PERSISTING","CHECKING").contains(w.status())
            && (!w.status().equals("CHECKING") || StyledAssetStore.motionResumeAllowed(w))
            && w.repairCount()<=StyledRecovery.limit(w.qualityPolicy(),false)
            && !derived(result) && !derived(w.providerResult()) && report!=null && !report.path("passed").asBoolean()
            && ((w.repairCount()==StyledRecovery.limit(w.qualityPolicy(),false)
                && replaceableFailure(report) && report.path("issues").isArray() && !report.path("issues").isEmpty())
                || paletteOnlyConflict(report));
    }
    /** A new exact approved pose removes color animation entirely; it does not settle the old observation. */
    static boolean paletteOnlyConflict(JsonNode report) {
        if(report==null || !StyledMotionReview.unresolved(report))return false;
        var reports=new ArrayList<JsonNode>();reports.add(report);
        for(String name:List.of("rawEditReview","restoredReview"))if(report.has(name))reports.add(report.path(name));
        boolean conflict=false;
        for(var r:reports) {
            if(!StyledMotionReview.VERSION.equals(r.path("motionReviewVersion").asText()) || !r.path("referencePoseUsable").asBoolean()
                || !r.path("uncertainProperties").isArray() || r.path("uncertainProperties").valueStream().anyMatch(n->!n.asText().equals("palette"))
                || !r.path("confirmedProperties").isArray() || r.path("confirmedProperties").valueStream().anyMatch(n->!n.asText().equals("palette")))return false;
            for(String field:List.of("edgeFrames","silhouetteFrames","detachedFrames","idleMotionFrames"))
                if(!r.path(field).isArray() || !r.path(field).isEmpty())return false;
            if(!r.path("issues").isArray() || r.path("issues").valueStream().anyMatch(n->!n.asText().equals("IDENTITY_DRIFT")))return false;
            conflict|=r.path("uncertainProperties").valueStream().anyMatch(n->n.asText().equals("palette"));
            int count=r.path("observationCount").asInt();if(count<1 || count>2 || r.has("consistencyReview")!=(count==2))return false;
            for(String name:count==1?List.of("initialVision"):List.of("initialVision","consistencyReview")) {
                var observation=r.path(name);
                try {StyledMotionReview.validate(observation,"IDLE");}catch(AssetException invalid){return false;}
                if(observation.path("properties").valueStream().anyMatch(p->!p.path("property").asText().equals("palette") && !p.path("state").asText().equals("PASS")))return false;
            }
        }return conflict;
    }
    /** Replacing a definitively moving IDLE does not resolve its uncertain loop verdict.
     * The old raw/restored reports remain rejected; the approved seed alternative is reviewed afresh.
     * Only loop uncertainty is independent of that replacement decision. Identity/tail/action doubt still holds.
     */
    static boolean replaceableFailure(JsonNode report) {
        if(!StyledMotionReview.unresolved(report))return report.path("motionDecision").asText().equals("CONFIRMED_DEFECT");
        var reports=new ArrayList<JsonNode>();reports.add(report);
        for(String name:List.of("rawEditReview","restoredReview"))if(report.has(name))reports.add(report.path(name));
        for(var r:reports) {
            if(!StyledMotionReview.VERSION.equals(r.path("motionReviewVersion").asText()) || r.path("passed").asBoolean()
                || !Set.of("CONFIRMED_DEFECT","UNCERTAIN").contains(r.path("motionDecision").asText())
                || r.path("observationCount").asInt()!=2 || !r.path("uncertainProperties").isArray()
                || r.path("uncertainProperties").valueStream().anyMatch(n->!n.asText().equals("loop"))
                || !r.path("issues").valueStream().anyMatch(n->n.asText().equals("IDLE_MOTION")))return false;
            for(String property:List.of("action","idleStillness")) {
                if(!r.path("confirmedProperties").valueStream().anyMatch(n->n.asText().equals(property)))return false;
                for(String view:List.of("initialVision","consistencyReview")) {
                    var p=r.path(view).path("properties").valueStream().filter(n->n.path("property").asText().equals(property)).toList();
                    if(p.size()!=1 || !p.getFirst().path("state").asText().equals("FAIL"))return false;
                }
            }
        }
        return true;
    }
    static JsonNode result(byte[] seed,JsonNode hashes,String direction,JsonNode previous,JsonMapper json) {
        if(!StyledSpriteCodec.DIRECTIONS.contains(direction) || StyledSpriteCodec.motionFrame(seed).getWidth()!=40
            || hashes.size()!=4 || !previous.path("sha256").asText().matches("[a-f0-9]{64}"))throw new AssetException(409,"IDLE_HOLD_INPUT_INVALID");
        // Every frame is the same approved, losslessly padded pose. No source motion is cropped or concealed.
        var r=json.createObjectNode().put("status","COMPLETED");
        r.set("frames",json.valueToTree(Collections.nCopies(9,Base64.getEncoder().encodeToString(seed))));
        var d=r.putObject("derivation").put("strategy",VERSION).put("motionKind","STATIC_IDLE").put("direction",direction)
            .put("sourceMotionSha256",previous.path("sha256").asText()).put("motionSeedSha256",StyledSpriteCodec.sha(seed));
        d.set("seedHashes",hashes);return r;
    }
}
