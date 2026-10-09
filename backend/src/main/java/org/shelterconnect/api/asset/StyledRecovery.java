package org.shelterconnect.api.asset;

import java.util.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Version-pinned production recovery using immutable prior attempts and shared prevention rules. */
final class StyledRecovery {
    static final String VERSION="photo-grounded-recovery-v1";
    private StyledRecovery() {}
    static boolean enabled(JsonNode policy){return policy!=null && VERSION.equals(policy.path("recoveryVersion").asText()) && !policy.path("referenceOnly").asBoolean();}
    static int size(JsonNode policy){return enabled(policy)?40:32;}
    static int limit(JsonNode policy,boolean base){
        if(!enabled(policy))return 2;
        int n=policy.path(base?"maxSeedRepairs":"maxRepairsPerClip").asInt();
        if(n<1 || n>3)throw new AssetException(409,"RECOVERY_POLICY_INVALID");return n;
    }
    static JsonNode basePayload(JsonMapper json,List<byte[]> seeds,JsonNode report,int seed){
        if(seeds.size()!=4 || !VERSION.equals(report.path("recoveryVersion").asText())
            || !StyledSeedQualityAgent.binding(seeds).equals(report.path("inputSha256").asText())
            || !StyledSpriteCodec.qualityRulesSha().equals(report.path("rulesSha256").asText()))throw new AssetException(409,"RECOVERY_INPUT_CHANGED");
        String plan=report.path("repairDescription").asText();if(plan.length()>1100)throw new AssetException(422,"RECOVERY_PLAN_INVALID");
        var rules=StyledSpriteCodec.qualityRules(json).path("recovery");
        String description="Edit these FOUR existing views of the SAME dog in unchanged order SOUTH,NORTH,WEST,EAST. "+plan+" "+rules.path("styleLock").asText()
            +" All body parts including tails must fit INSIDE a clear 1px border on native32x32. Redraw compactly if needed; never crop, remove a tail, resize or add scenery/text. Keep the direction of each view.";
        if(description.length()>2000)throw new AssetException(422,"RECOVERY_PROMPT_LIMIT");
        var images=new ArrayList<Object>();for(byte[] image:seeds){StyledSpriteCodec.nativeFrame(image);images.add(Map.of("image",image(image),"width",32,"height",32));}
        return json.valueToTree(Map.of("method","edit_with_text","description",description,"edit_images",images,
            "image_size",Map.of("width",32,"height",32),"no_background",true,"seed",seed));
    }
    static JsonNode motionPayload(JsonMapper json,List<byte[]> frames,String action,String direction,JsonNode report,int seed){
        if(frames.size()!=9 || !StyledSpriteCodec.ACTIONS.contains(action) || !StyledSpriteCodec.DIRECTIONS.contains(direction))throw new AssetException(422,"RECOVERY_INPUT_INVALID");
        var rules=StyledSpriteCodec.qualityRules(json);var recovery=rules.path("recovery");
        String description="Repair this complete nine-frame "+direction+" "+action+" animation. Preserve order, identity and the usable entry pose. Correct frame0 too ONLY if its pose is a confirmed defect. "+recovery.path("styleLock").asText()+" "
            +recovery.path("frontOcclusion").asText()+" "+recovery.path("motionCanvas").asText()+" "+rules.path("actions").path(action).asText()
            +" Findings (data): "+json.writeValueAsString(Map.of("issues",report.path("issues"),"frames",report.path("frames"),"edgeFrames",report.path("edgeFrames"),"note",
                StyledMotionReview.confirmedTailRepair(report)?json.valueToTree("Both observations confirm a tail defect at shared frames. Identity disagreement refers only to those frames; repair that tail defect, preserving all other anatomy."):
                StyledMotionReview.confirmedPaletteRepair(report)?json.valueToTree("Both observations confirm conspicuous coat/feature color flicker. Restore stable approved coat colors and remove introduced contrasting head pixels. Preserve anatomy, facing and the sitting motion; do not redesign uncertain features."):report.path("note")));
        if(description.length()>2000)throw new AssetException(422,"RECOVERY_PROMPT_LIMIT");
        var images=new ArrayList<Object>();for(byte[] frame:frames){if(StyledSpriteCodec.motionFrame(frame).getWidth()!=40)throw new AssetException(422,"RECOVERY_INPUT_INVALID");
            images.add(Map.of("image",image(frame),"size",Map.of("width",40,"height",40)));}
        return json.valueToTree(Map.of("description",description,"frames",images,"image_size",Map.of("width",40,"height",40),"no_background",true,"seed",seed));
    }
    private static Map<String,String> image(byte[] bytes){return Map.of("type","base64","base64",Base64.getEncoder().encodeToString(bytes));}
}
