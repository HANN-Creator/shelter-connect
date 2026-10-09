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
            && w.repairCount()==StyledRecovery.limit(w.qualityPolicy(),false)
            && !derived(result) && !derived(w.providerResult()) && report!=null && !report.path("passed").asBoolean()
            && !StyledMotionReview.unresolved(report) && report.path("issues").isArray() && !report.path("issues").isEmpty();
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
