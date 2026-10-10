package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class StyledIdleWarningPlanTest {
    final StyledMotionRepairEvidenceTest fixture=new StyledMotionRepairEvidenceTest();
    final Path root=StyledMotionRepairEvidenceTest.ROOT.resolve("deployed-warning");
    ObjectNode report()throws Exception{return (ObjectNode)fixture.json.readTree(Files.readAllBytes(root.resolve("idle-west-review.json")));}
    @Test void actualNonblockingPaletteWarningDoesNotBlockNewStaticCandidateOrAlterOldVerdict()throws Exception {
        var r=report();var before=r.deepCopy();
        var evidence=fixture.json.readTree(Files.readAllBytes(root.resolve("evidence.json")));
        assertThat(StyledSpriteCodec.sha(Files.readAllBytes(root.resolve("idle-west-review.json")))).isEqualTo(evidence.path("reportSha256").asText());
        assertThat(StyledSpriteCodec.sha(Files.readAllBytes(root.resolve("../idle-west.png")))).isEqualTo(evidence.path("imageSha256").asText());
        assertThat(StyledAestheticPolicy.permitsPaletteWarning(r)).isTrue();
        assertThat(StyledIdleHold.motionOnlyFailure(r)).isTrue();
        assertThat(r).isEqualTo(before);assertThat(StyledMotionReview.boundPass(r)).isFalse();
        var policy=fixture.json.createObjectNode().put("recoveryVersion",StyledRecovery.VERSION).put("idleHoldVersion",StyledIdleHold.VERSION)
            .put("rulesSha256",StyledSpriteCodec.qualityRulesSha()).put("maxRepairsPerClip",3);
        var previous=fixture.json.createObjectNode().put("sha256",evidence.path("imageSha256").asText());
        var helper=new StyledIdleHoldTest();
        for(String state:List.of("WAITING","PERSISTING"))assertThat(StyledIdleHold.eligible(helper.work("IDLE",3,state,policy,previous),r,previous)).isTrue();
        for(String action:List.of("WALK","SIT"))assertThat(StyledIdleHold.eligible(helper.work(action,3,"PERSISTING",policy,previous),r,previous)).isFalse();
        assertThat(StyledIdleHold.eligible(helper.work("IDLE",2,"PERSISTING",policy,previous),r,previous)).isFalse();
    }
    @Test void missingOrBlockingWarningConfirmedFlickerAndAnatomyDoubtStillStopReplacement()throws Exception {
        for(String mutation:List.of("policy","warning","blocking","confirmed")) {
            var r=report();switch(mutation) {
                case "policy" -> r.remove("aestheticPolicy");
                case "warning" -> r.remove("qualityWarnings");
                case "blocking" -> ((ObjectNode)r.at("/qualityWarnings/0")).put("blocking",true);
                case "confirmed" -> ((ObjectNode)StyledMotionReview.property(r.path("initialVision"),"palette")).put("state","FAIL");
            }
            assertThat(StyledIdleHold.motionOnlyFailure(r)).as(mutation).isFalse();
        }
        for(String key:List.of("identity","eyes","limbs","direction","referencePose","tail"))for(String state:List.of("FAIL","UNCERTAIN")) {
            var r=report();((ObjectNode)StyledMotionReview.property(r.path("initialVision"),key)).put("state",state).putArray("frames").add(0);
            assertThat(StyledIdleHold.motionOnlyFailure(r)).as(key+state).isFalse();
        }
        // Every raw/restored layer must independently permit its warning.
        var r=report();var nested=report();nested.remove("qualityWarnings");r.set("rawEditReview",nested);
        assertThat(StyledIdleHold.motionOnlyFailure(r)).isFalse();
    }
}
