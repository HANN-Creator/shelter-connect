package org.shelterconnect.api.asset;

import java.util.*;
import tools.jackson.databind.json.JsonMapper;

/** Build each persisted policy once; legacy jobs keep their original repair contract. */
final class StyledAssetPolicy {
    private StyledAssetPolicy() {}

    private static Map<String,Object> common(JsonMapper json) {
        var policy=new LinkedHashMap<String,Object>();
        policy.put("version",StyledQualityAgent.VERSION);
        policy.put("rulesRevision",StyledSpriteCodec.qualityRules(json).path("revision").asText());
        policy.put("rulesSha256",StyledSpriteCodec.qualityRulesSha());
        policy.put("lowTailRepair","REGENERATE_THEN_EDIT_ONCE");
        policy.put("idleRepair",StyledSpriteCodec.IDLE_EDIT_VERSION);
        policy.put("marginRepair",StyledSpriteCodec.MARGIN_EDIT_VERSION);
        policy.put("seedIdleRepair",StyledSpriteCodec.SEED_IDLE_VERSION);
        return policy;
    }
    static Map<String,Object> legacyMotion(JsonMapper json) {
        var policy=common(json);
        policy.put("maxRepairsPerClip",2);
        return policy;
    }
    static Map<String,Object> legacySeed(JsonMapper json) {
        var policy=legacyMotion(json);
        policy.put("seedQualityVersion",StyledSeedQualityAgent.VERSION);
        return policy;
    }
    static Map<String,Object> current(JsonMapper json,boolean advisory) {
        var policy=common(json);
        var recovery=StyledSpriteCodec.qualityRules(json).path("recovery");
        policy.put("seedQualityVersion",StyledSeedQualityAgent.VERSION);
        policy.put("lessonRevision",StyledLessonStore.REVISION_VERSION);
        policy.put("automaticApproval",StyledAutoApproval.VERSION);
        policy.put("aestheticPolicy",StyledAestheticPolicy.VERSION);
        policy.put("qualityTimeoutVersion",StyledQualityTimeout.VERSION);
        policy.put("structuralRecovery",StyledStructuralRecovery.VERSION);
        policy.put("idleSeedRestart",StyledIdleRestart.VERSION);
        policy.put("recoveryVersion",StyledRecovery.VERSION);
        policy.put("seedRepairVersion",StyledSeedRepair.VERSION);
        policy.put("seedTailEvidenceVersion",StyledTailAnatomy.VERSION);
        policy.put("motionFrameSize",40);
        policy.put("seedMotionMargin",1);
        policy.put("maxSeedRepairs",recovery.path("maxSeedRepairs").asInt());
        policy.put("maxRepairsPerClip",recovery.path("maxMotionRepairs").asInt());
        policy.put("motionCandidateVersion",StyledMotionCandidate.VERSION);
        policy.put("idleHoldVersion",StyledIdleHold.VERSION);
        if(advisory)policy.put("visualWarnings",StyledVisualWarnings.VERSION);
        return policy;
    }
}
