package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;

class StyledIdleStillnessConflictTest {
    final JsonMapper json=JsonMapper.builder().build();
    final Path root=Path.of("scripts/fixtures/idle-stillness-v36");
    JsonNode prior()throws Exception{return json.readTree(Files.readAllBytes(root.resolve("previous-review.json")));}
    List<byte[]> seeds()throws Exception {var s=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)s.add(Files.readAllBytes(root.resolve("seeds/"+d+".png")));return s;}
    StyledAssetStore.Work work(String action,int count,JsonNode result) {
        var p=json.createObjectNode().put("recoveryVersion",StyledRecovery.VERSION).put("idleHoldVersion",StyledIdleHold.VERSION)
            .put("rulesSha256",StyledSpriteCodec.qualityRulesSha()).put("maxRepairsPerClip",3);
        return new StyledAssetStore.Work(UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),"idle-west",action,"west","PERSISTING",null,null,"dog-photos","photo.png",json.createObjectNode(),null,result,null,count,p);
    }
    @Test void actualConflictKeepsBothVerdictsAndRequiresExhaustedOriginalIdleBudget()throws Exception {
        var r=prior();var original=r.deepCopy();var previous=json.readTree(Files.readAllBytes(root.resolve("previous-result.json")));
        var receipt=json.readTree(Files.readAllBytes(root.resolve("evidence.json")));
        for(var e:receipt.path("hashes").properties())assertThat(StyledSpriteCodec.sha(Files.readAllBytes(root.resolve(e.getKey())))).isEqualTo(e.getValue().asText());
        assertThat(StyledIdleHold.stillnessOnlyConflict(r)).isTrue();
        assertThat(StyledIdleHold.eligible(work("IDLE",3,previous),r,previous)).isTrue();
        for(int count:List.of(0,1,2,4))assertThat(StyledIdleHold.eligible(work("IDLE",count,previous),r,previous)).isFalse();
        for(String action:List.of("WALK","SIT","TAIL_WAG"))assertThat(StyledIdleHold.eligible(work(action,3,previous),r,previous)).isFalse();
        var hashes=json.createObjectNode();var seeds=seeds();for(int i=0;i<4;i++)hashes.put(StyledSpriteCodec.DIRECTIONS.get(i),StyledSpriteCodec.sha(seeds.get(i)));
        var exact=StyledSpriteCodec.paddedSeed(seeds.get(2));var derived=StyledIdleHold.result(exact,hashes,"west",previous,json);
        assertThat(derived.path("frames").size()).isEqualTo(9);
        for(var f:derived.path("frames"))assertThat(Base64.getDecoder().decode(f.asText())).isEqualTo(exact);
        assertThat(StyledIdleHold.eligible(work("IDLE",3,derived),r,derived)).isFalse();
        assertThat(r).isEqualTo(original);assertThat(StyledMotionReview.boundPass(r)).isFalse();
        var actualStatic=json.readTree(Files.readAllBytes(root.resolve("actual-static-review.json")));
        assertThat(actualStatic.path("passed").asBoolean()).isTrue();
        assertThat(actualStatic.path("reviewedFrameHashes").size()).isEqualTo(9);
        assertThat(actualStatic.path("reviewedFrameHashes").valueStream().map(JsonNode::asText).distinct().toList()).containsExactly(StyledSpriteCodec.sha(exact));
    }
    @Test void everyIndependentDoubtPixelDefectMalformedOrOneSidedObservationStillBlocks()throws Exception {
        for(String review:List.of("main","rawEditReview","restoredReview")) {
            for(String property:StyledMotionReview.PROPERTIES.keySet())if(!property.equals("idleStillness"))for(String state:List.of("FAIL","UNCERTAIN")) {
                var r=prior();var target=review.equals("main")?r:r.path(review);
                ((ObjectNode)StyledMotionReview.property(target.path("initialVision"),property)).put("state",state);
                assertThat(StyledIdleHold.stillnessOnlyConflict(r)).as(review+property+state).isFalse();
            }
            for(String field:List.of("issues","edgeFrames","silhouetteFrames","detachedFrames","idleMotionFrames","confirmedProperties")) {
                var r=prior();var target=(ObjectNode)(review.equals("main")?r:r.path(review));target.putArray(field).add(8);
                assertThat(StyledIdleHold.stillnessOnlyConflict(r)).as(review+field).isFalse();
            }
            for(String state:List.of("PASS","UNCERTAIN")) {
                var r=prior();var target=review.equals("main")?r:r.path(review);
                ((ObjectNode)StyledMotionReview.property(target.path("initialVision"),"idleStillness")).put("state",state);
                assertThat(StyledIdleHold.stillnessOnlyConflict(r)).isFalse();
            }
            var r=prior();var target=(ObjectNode)(review.equals("main")?r:r.path(review));target.remove("consistencyReview");
            assertThat(StyledIdleHold.stillnessOnlyConflict(r)).isFalse();
        }
        assertThat(StyledIdleHold.stillnessOnlyConflict(json.createObjectNode())).isFalse();
    }
    @Test void liveExactApprovedWestPoseUsesOneFreshReviewAndPreservesPriorConflict()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("IDLE_HOLD_LIVE_APPROVED")));
        var out=Path.of(System.getenv("IDLE_HOLD_LIVE_OUTPUT"));Files.createDirectories(out);
        var sources=seeds().stream().map(StyledSpriteCodec::paddedSeed).toList();var frames=Collections.nCopies(9,sources.get(2));
        var saved=out.resolve("west-static.json");var intent=out.resolve("west-static-intent.json");JsonNode r;
        if(Files.exists(saved))r=json.readTree(Files.readAllBytes(saved));else {
            assertThat(Files.exists(intent)).as("Do not blindly repeat an unknown paid call").isFalse();
            Files.write(intent,json.writeValueAsBytes(Map.of("sourceFrameSha256",StyledSpriteCodec.sha(sources.get(2)),"frameCount",9,"rulesSha256",StyledSpriteCodec.qualityRulesSha())),StandardOpenOption.CREATE_NEW);
            var props=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
            r=new StyledQualityAgent(new OpenAiResponsesClient(props,json),props,json).review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),sources,frames,"IDLE","west");
            Files.write(saved,json.writeValueAsBytes(r),StandardOpenOption.CREATE_NEW);
        }
        assertThat(r.path("rulesSha256").asText()).isEqualTo(StyledSpriteCodec.qualityRulesSha());
        System.out.println("Actual static west: "+r.path("motionDecision")+" issues="+r.path("issues")+" uncertainty="+r.path("uncertainProperties"));
        assertThat(r.path("passed").asBoolean()).as("Real result retained; do not reroll until PASS").isTrue();
        assertThat(prior().path("motionDecision").asText()).isEqualTo("UNCERTAIN");
    }
}
