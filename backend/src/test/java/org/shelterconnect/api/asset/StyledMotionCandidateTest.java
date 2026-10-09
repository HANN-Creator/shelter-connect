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
    @ParameterizedTest @ValueSource(strings={"sit-south","sit-north","walk-south"})
    void realConflictsProposeAnAlternativeWithoutRelabelingEvidence(String label)throws Exception {
        var r=actual(label);var unchanged=r.deepCopy();String action=label.startsWith("sit")?"SIT":"WALK";
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
        var r=(ObjectNode)actual("sit-north");
        switch(defect) {
            case "unknown-only" -> {for(var p:r.path("initialVision").path("properties"))if(!p.path("state").asText().equals("PASS"))((ObjectNode)p).put("state","UNCERTAIN");}
            case "reference" -> ((ObjectNode)StyledMotionReview.property(r.path("initialVision"),"referencePose")).put("state","UNCERTAIN");
            case "missing-observation" -> r.remove("consistencyReview");
            case "missing-frame" -> ((ObjectNode)StyledMotionReview.property(r.path("initialVision"),"palette")).putArray("frames");
            case "bad-frame" -> ((ObjectNode)StyledMotionReview.property(r.path("initialVision"),"palette")).putArray("frames").add(9);
            case "stale" -> r.put("rulesSha256","0".repeat(64));
            case "passed" -> r.put("passed",true);
        }
        assertThat(StyledMotionCandidate.plan(r,"SIT",json)).isNull();
    }
}
