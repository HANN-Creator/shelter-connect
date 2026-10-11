package org.shelterconnect.api.asset;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Quarantine opaque/empty BASE output; regenerate from the original photo, never erase white pixels. */
final class StyledStructuralRecovery {
    static final String VERSION="base-alpha-restart-v1";
    private StyledStructuralRecovery() {}
    static boolean enabled(JsonNode policy) {
        return StyledRecovery.enabled(policy) && VERSION.equals(policy.path("structuralRecovery").asText());
    }
    static boolean report(JsonNode report) {
        return report!=null && VERSION.equals(report.path("version").asText()) && !report.path("passed").asBoolean()
            && StyledSpriteCodec.qualityRulesSha().equals(report.path("rulesSha256").asText())
            && report.path("issues").size()==1 && "STYLED_ALPHA_INVALID".equals(report.path("issues").get(0).asText());
    }
    static JsonNode inspect(Map<String,byte[]> seeds,JsonMapper json) {
        var measurements=json.createObjectNode();var failed=json.createArrayNode();
        for(String direction:StyledSpriteCodec.DIRECTIONS) {
            var frame=StyledSpriteCodec.rawNativeFrame(seeds.get(direction));int clear=0,visible=0;
            for(int y=0;y<32;y++)for(int x=0;x<32;x++){int a=frame.getRGB(x,y)>>>24;if(a==0)clear++;else visible++;}
            measurements.set(direction,json.valueToTree(Map.of("transparentPixels",clear,"visiblePixels",visible)));
            if(clear==0 || visible==0)failed.add(direction);
        }
        if(failed.isEmpty())return null;
        var report=json.createObjectNode().put("version",VERSION).put("passed",false).put("visualReviewPerformed",false)
            .put("rulesSha256",StyledSpriteCodec.qualityRulesSha()).put("repairStrategy","REGENERATE_FROM_PHOTO");
        report.putArray("issues").add("STYLED_ALPHA_INVALID");report.set("failedDirections",failed);report.set("alphaMeasurements",measurements);
        return report;
    }
}
