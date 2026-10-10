package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class StyledMotionCandidateTest {
    final JsonMapper json=JsonMapper.builder().build();
    JsonNode actual(String label)throws Exception {
        return json.readTree(Files.readAllBytes(Path.of("scripts/fixtures/native-rgba-v29/"+label+"-v30-review.json")));
    }
    JsonNode replay(String label)throws Exception {
        var r=(ObjectNode)actual(label);
        // Synthetic current-policy replay of archived observations, never a provider receipt.
        r.put("rulesSha256",StyledSpriteCodec.qualityRulesSha());
        for(String key:List.of("rawEditReview","restoredReview"))if(r.has(key))((ObjectNode)r.path(key)).put("rulesSha256",StyledSpriteCodec.qualityRulesSha());
        return r;
    }
    @Test void historicalReceiptCannotAuthorizeACandidateAfterRulesChange()throws Exception {
        var old=actual("sit-north");assertThat(old.path("rulesSha256").asText()).isNotEqualTo(StyledSpriteCodec.qualityRulesSha());
        assertThat(StyledMotionCandidate.plan(old,"SIT",json)).isNull();
    }
    @ParameterizedTest @ValueSource(strings={"sit-south","sit-north","walk-south"})
    void realConflictsProposeAnAlternativeWithoutRelabelingEvidence(String label)throws Exception {
        var r=replay(label);var unchanged=r.deepCopy();String action=label.startsWith("sit")?"SIT":"WALK";
        var plan=StyledMotionCandidate.plan(r,action,json);
        assertThat(plan).isNotNull();assertThat(plan.path("assessment").asText()).isEqualTo("UNCONFIRMED_CANDIDATE_ONLY");
        assertThat(plan.path("frames").size()).isGreaterThan(0);assertThat(r).isEqualTo(unchanged);
        assertThat(StyledMotionReview.unresolved(r)).isTrue();assertThat(r.path("passed").asBoolean()).isFalse();
        var frames=StyledSpriteCodec.frames(Files.readAllBytes(Path.of("scripts/fixtures/sit-conflicts-v28/sit-north-restored.png")));
        for(String a:StyledSpriteCodec.ACTIONS)for(String d:StyledSpriteCodec.DIRECTIONS) {
            // Same typed finding payload must fit even the longest action prompt.
            var payload=StyledRecovery.motionPayload(json,frames,a,d,r,1);
            assertThat(payload.path("description").asText()).hasSizeLessThanOrEqualTo(2000);
        }
    }
    @ParameterizedTest @ValueSource(strings={"unknown-only","reference","missing-observation","missing-frame","bad-frame","stale","passed"})
    void unsupportedOrUnboundObservationsCannotBuyACandidate(String defect)throws Exception {
        var r=(ObjectNode)replay("sit-north");
        switch(defect) {
            case "unknown-only" -> {for(var layer:List.of(r,r.path("rawEditReview"),r.path("restoredReview")))for(String observer:List.of("initialVision","consistencyReview"))for(var p:layer.path(observer).path("properties"))if(!p.path("state").asText().equals("PASS"))((ObjectNode)p).put("state","UNCERTAIN");}
            case "reference" -> ((ObjectNode)StyledMotionReview.property(r.path("initialVision"),"referencePose")).put("state","UNCERTAIN");
            case "missing-observation" -> r.remove("consistencyReview");
            case "missing-frame" -> ((ObjectNode)StyledMotionReview.property(r.path("initialVision"),"palette")).putArray("frames");
            case "bad-frame" -> ((ObjectNode)StyledMotionReview.property(r.path("initialVision"),"palette")).putArray("frames").add(9);
            case "stale" -> r.put("rulesSha256","0".repeat(64));
            case "passed" -> r.put("passed",true);
        }
        assertThat(StyledMotionCandidate.plan(r,"SIT",json)).isNull();
    }
    @Test void realLoopTargetDoesNotInventUncertainTailAndNeverRelabelsEvidence()throws Exception {
        Path root=Path.of("scripts/fixtures/confirmed-motion-v37");
        var r=(ObjectNode)json.readTree(Files.readAllBytes(root.resolve("walk-south-review.json")));
        byte[] sheet=Files.readAllBytes(root.resolve("walk-south.png"));
        assertThat(StyledSpriteCodec.sha(sheet)).isEqualTo(r.path("inputSha256").asText());
        r.put("rulesSha256",StyledSpriteCodec.qualityRulesSha());
        for(String key:List.of("rawEditReview","restoredReview"))if(r.has(key))((ObjectNode)r.path(key)).put("rulesSha256",StyledSpriteCodec.qualityRulesSha());
        var before=r.deepCopy();var plan=StyledMotionCandidate.plan(r,"WALK",json);
        assertThat(plan).isNotNull();assertThat(plan.path("assessment").asText()).isEqualTo("UNCONFIRMED_CANDIDATE_ONLY");
        assertThat(plan.path("properties").valueStream().map(JsonNode::asText).toList()).containsExactly("loop");
        assertThat(plan.path("preservedUncertainProperties").valueStream().map(JsonNode::asText).toList()).containsExactly("tail");
        assertThat(plan.path("issues").toString()).isEqualTo("[\"DISCONTINUITY\"]");
        assertThat(r).isEqualTo(before);assertThat(StyledMotionReview.boundPass(r)).isFalse();
        var payload=StyledRecovery.motionPayload(json,StyledSpriteCodec.frames(sheet),"WALK","south",r,1);
        assertThat(payload.path("description").asText()).contains("[loop] only", "including [tail]").doesNotContain("TAIL_CARRIAGE").hasSizeLessThanOrEqualTo(2000);
    }

}
