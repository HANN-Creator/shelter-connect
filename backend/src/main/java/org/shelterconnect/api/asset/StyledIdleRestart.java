package org.shelterconnect.api.asset;

import java.util.*;
import tools.jackson.databind.JsonNode;

/** One cheaper fresh idle attempt from the approved seed, only for confirmed excessive motion. */
final class StyledIdleRestart {
    static final String VERSION="approved-idle-restart-once-v1";
    private StyledIdleRestart() {}
    static boolean eligible(StyledAssetStore.Work w) {
        if(!w.action().equals("IDLE") || w.repairCount()!=1 || w.status().equals("CHECKING")
            || !StyledRecovery.enabled(w.qualityPolicy()) || !VERSION.equals(w.qualityPolicy().path("idleSeedRestart").asText()))return false;
        return confirmed(w.qualityReport());
    }
    static boolean confirmed(JsonNode r) {
        if(r==null || r.has("rawEditReview") || r.has("restoredReview") || r.path("passed").asBoolean()
            || !StyledMotionReview.VERSION.equals(r.path("motionReviewVersion").asText())
            || !StyledSpriteCodec.qualityRulesSha().equals(r.path("rulesSha256").asText())
            || r.path("observationCount").asInt()!=2 || !r.path("referencePoseUsable").asBoolean()
            || !r.path("issues").isArray() || !r.path("issues").valueStream().anyMatch(n->n.asText().equals("IDLE_MOTION"))
            || r.path("issues").valueStream().anyMatch(n->!Set.of("IDLE_MOTION","ACTION_MISSING","TAIL_CARRIAGE").contains(n.asText())))return false;
        for(String key:List.of("edgeFrames","detachedFrames"))
            if(!r.path(key).isArray() || !r.path(key).isEmpty())return false;
        try {
            for(String key:List.of("initialVision","consistencyReview")) {
                var o=r.path(key);StyledMotionReview.validate(o,"IDLE");
                if(!StyledMotionReview.property(o,"idleStillness").path("state").asText().equals("FAIL")
                    && r.path("idleMotionFrames").isEmpty())return false;
                for(String property:StyledMotionReview.PROPERTIES.keySet()) {
                    String state=StyledMotionReview.property(o,property).path("state").asText();
                    if(Set.of("action","idleStillness","tail").contains(property)){if(state.equals("UNCERTAIN"))return false;}
                    else if(!state.equals("PASS"))return false;
                }
            }
            // A disputed tail never becomes a fresh attempt under this recipe.
            if(!StyledMotionReview.property(r.path("initialVision"),"tail").path("state")
                .equals(StyledMotionReview.property(r.path("consistencyReview"),"tail").path("state")))return false;
        }catch(AssetException invalid){return false;}
        return true;
    }
}
