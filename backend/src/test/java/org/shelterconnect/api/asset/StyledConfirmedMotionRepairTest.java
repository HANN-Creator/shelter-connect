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

class StyledConfirmedMotionRepairTest {
    final JsonMapper json=JsonMapper.builder().build();
    final Path root=Path.of("scripts/fixtures/confirmed-motion-v37");
    JsonNode actual()throws Exception{return json.readTree(Files.readAllBytes(root.resolve("review.json")));}
    List<JsonNode> layers(JsonNode r){return List.of(r,r.path("rawEditReview"),r.path("restoredReview"));}
    JsonNode replay()throws Exception {
        var r=actual();for(var layer:layers(r))((ObjectNode)layer).put("rulesSha256",StyledSpriteCodec.qualityRulesSha());
        return r; // Synthetic current-policy replay; archived original remains unchanged.
    }
    @Test void realNineFramesExplainTheRepairGateWithoutChangingTheVerdict()throws Exception {
        var evidence=json.readTree(Files.readAllBytes(root.resolve("evidence.json")));
        for(var e:evidence.path("hashes").properties())assertThat(StyledSpriteCodec.sha(Files.readAllBytes(root.resolve(e.getKey())))).isEqualTo(e.getValue().asText());
        var frames=StyledSpriteCodec.frames(Files.readAllBytes(root.resolve("idle-north.png")));
        assertThat(frames).hasSize(9);
        assertThat(frames.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(actual().path("frameHashes").valueStream().map(JsonNode::asText).toList());
        assertThat(StyledConfirmedMotionRepair.plan(actual(),"IDLE",json)).isNull(); // old rules are not current evidence
        var r=replay();var unchanged=r.deepCopy();
        assertThat(StyledMotionReview.confirmedTailRepair(r)).isFalse(); // original regression
        assertThat(StyledMotionReview.confirmedPaletteRepair(r)).isFalse();
        var plan=StyledConfirmedMotionRepair.plan(r,"IDLE",json);
        assertThat(plan).isNotNull();assertThat(plan.path("assessment").asText()).isEqualTo("CONFIRMED_TARGET_ONLY");
        assertThat(plan.path("properties").valueStream().map(JsonNode::asText).toList()).containsExactly("action","identity","palette","tail");
        assertThat(plan.path("frames").valueStream().map(JsonNode::asInt).toList()).containsExactly(1,2,3,4,5,6,7);
        assertThat(plan.path("properties").toString()).doesNotContain("idleStillness");
        assertThat(r).isEqualTo(unchanged);assertThat(StyledMotionReview.unresolved(r)).isTrue();assertThat(StyledMotionReview.boundPass(r)).isFalse();
        var payload=StyledRecovery.motionPayload(json,frames,"IDLE","north",r,1);
        assertThat(payload.path("frames").size()).isEqualTo(9);
        assertThat(payload.path("description").asText()).contains("Both observations confirm these targets", "Other disputed properties remain unconfirmed", "coat markings").hasSizeLessThanOrEqualTo(2000);
    }
    @ParameterizedTest @ValueSource(strings={"missing-second","malformed","bad-frame","text-frame","disjoint","unknown-only","raw-pass","restored-disjoint","entry-uncertain","stale","passed","false-summary"})
    void noPaidRepairWithoutTwoValidMatchingObservationsAndUsableEntry(String mutation)throws Exception {
        var r=(ObjectNode)replay();
        switch(mutation) {
            case "missing-second" -> r.remove("consistencyReview");
            case "malformed" -> ((ObjectNode)r.path("initialVision")).putArray("properties");
            case "bad-frame","text-frame" -> {var p=(ObjectNode)StyledMotionReview.property(r.path("initialVision"),"tail");if(mutation.equals("bad-frame"))p.putArray("frames").add(9);else p.putArray("frames").add("1");}
            case "disjoint","restored-disjoint" -> {var layer=mutation.equals("disjoint")?r:r.path("restoredReview");for(var p:layer.at("/consistencyReview/properties"))if(p.path("state").asText().equals("FAIL"))((ObjectNode)p).putArray("frames").add(0);}
            case "unknown-only" -> {for(var layer:layers(r))for(var p:layer.at("/initialVision/properties"))if(p.path("state").asText().equals("FAIL"))((ObjectNode)p).put("state","UNCERTAIN");}
            case "raw-pass" -> ((ObjectNode)r.path("rawEditReview")).put("passed",true);
            case "entry-uncertain" -> ((ObjectNode)StyledMotionReview.property(r.path("initialVision"),"referencePose")).put("state","UNCERTAIN");
            case "stale" -> ((ObjectNode)r.path("restoredReview")).put("rulesSha256","0".repeat(64));
            case "passed" -> r.put("passed",true);
            case "false-summary" -> {for(var p:r.at("/consistencyReview/properties"))((ObjectNode)p).put("state","PASS");}
        }
        assertThat(StyledConfirmedMotionRepair.plan(r,"IDLE",json)).isNull();
    }
    @Test void uncertaintyNeverBecomesARepairTargetAndOnlyCommonOriginalRestoredPropertiesAreUsed()throws Exception {
        var r=replay();for(var layer:layers(r))((ObjectNode)StyledMotionReview.property(layer.path("initialVision"),"eyes")).put("state","UNCERTAIN");
        // Palette is no longer corroborated by the independent raw-image observer.
        ((ObjectNode)StyledMotionReview.property(r.at("/rawEditReview/consistencyReview"),"palette")).put("state","PASS");
        var plan=StyledConfirmedMotionRepair.plan(r,"IDLE",json);
        assertThat(plan.path("properties").valueStream().map(JsonNode::asText).toList()).containsExactly("action","identity","tail");
        assertThat(StyledMotionReview.unresolved(r)).isTrue();
    }
    @Test void actualBoundedEditsAndSeparatelyReviewedStaticFallbackRemainAuditable()throws Exception {
        var live=root.resolve("live-validation");var evidence=json.readTree(Files.readAllBytes(live.resolve("evidence.json")));
        for(var e:evidence.path("hashes").properties())assertThat(StyledSpriteCodec.sha(Files.readAllBytes(live.resolve(e.getKey())))).isEqualTo(e.getValue().asText());
        for(String name:List.of("edit-2","edit-3")) {
            var review=json.readTree(Files.readAllBytes(live.resolve(name+"-review.json")));
            assertThat(review.path("passed").asBoolean()).isFalse();assertThat(review.path("motionDecision").asText()).isEqualTo("CONFIRMED_DEFECT");
            assertThat(StyledSpriteCodec.frames(Files.readAllBytes(live.resolve(name+".png")))).hasSize(9);
        }
        var hold=StyledSpriteCodec.frames(Files.readAllBytes(live.resolve("static.png")));
        byte[] exact=StyledSpriteCodec.paddedSeed(Files.readAllBytes(root.resolve("seeds/north.png")));
        for(var f:hold)assertThat(StyledSpriteCodec.motionFrame(f).getRGB(0,0,40,40,null,0,40))
            .isEqualTo(StyledSpriteCodec.motionFrame(exact).getRGB(0,0,40,40,null,0,40));
        var review=json.readTree(Files.readAllBytes(live.resolve("static-review.json")));
        assertThat(review.path("passed").asBoolean()).isTrue();
        assertThat(review.path("reviewedFrameHashes").valueStream().map(JsonNode::asText).toList()).isEqualTo(hold.stream().map(StyledSpriteCodec::sha).toList());
        assertThat(evidence.path("automaticPackApproval").asBoolean()).isFalse();
    }
    @Test void liveSavedTargetGetsOneEditAndFreshFullReview()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("CONFIRMED_MOTION_LIVE_APPROVED")));
        var out=Path.of(System.getenv("CONFIRMED_MOTION_LIVE_OUTPUT"));Files.createDirectories(out);
        byte[] source=Files.readAllBytes(root.resolve("idle-north.png"));var before=replay();
        // Reproduce the saved observations to exercise the new repair decision; never call this a new quality verdict.
        var payload=StyledRecovery.motionPayload(json,StyledSpriteCodec.frames(source),"IDLE","north",before,2026101085);
        String requestSha=StyledSpriteCodec.sha(json.writeValueAsBytes(payload));
        var intent=out.resolve("intent.json");var ack=out.resolve("ack.json");var completed=out.resolve("provider-result.json");
        var provider=new StyledPixelLabClient(new AssetProperties(true,false,System.getenv("PIXELLAB_API_KEY"),"sb_secret_local_test","dog-photos","dog-assets"),json);
        UUID id;
        if(Files.exists(ack)) {
            assertThat(json.readTree(Files.readAllBytes(intent)).path("requestSha256").asText()).isEqualTo(requestSha);
            id=UUID.fromString(json.readTree(Files.readAllBytes(ack)).path("id").asText());
        } else {
            assertThat(Files.exists(intent)).as("Never resubmit an unknown paid outcome").isFalse();
            var receipt=json.createObjectNode().put("sourceSha256",StyledSpriteCodec.sha(source)).put("requestSha256",requestSha)
                .put("rulesSha256",StyledSpriteCodec.qualityRulesSha()).put("sourceReviewKind","archived-v36-observations-replayed-for-repair-only")
                .put("productionPublished",false).put("automaticApproval",false);
            receipt.set("repairPlan",StyledConfirmedMotionRepair.plan(before,"IDLE",json));
            Files.writeString(intent,json.writeValueAsString(receipt));Files.writeString(out.resolve("prompt.txt"),payload.path("description").asText());
            id=provider.editAnimation(payload);Files.writeString(ack,json.writeValueAsString(Map.of("id",id.toString())));
        }
        JsonNode response=Files.exists(completed)?json.readTree(Files.readAllBytes(completed)):null;
        for(int poll=0;response==null && poll<90;poll++) {
            var r=provider.poll(id,false);if(r.path("status").asText().equals("COMPLETED")){response=r;Files.writeString(completed,json.writeValueAsString(r));}
            else {assertThat(r.path("status").asText()).isEqualTo("WAITING");Thread.sleep(5000);}
        }
        assertThat(response).isNotNull();
        var frames=response.path("frames").valueStream().map(n->Base64.getDecoder().decode(n.asText())).toList();
        var seeds=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)seeds.add(StyledSpriteCodec.paddedSeed(Files.readAllBytes(root.resolve("seeds/"+d+".png"))));
        var raw=StyledSpriteCodec.rawSheet(frames);Files.write(out.resolve("edited.png"),raw);
        var reviewFile=out.resolve("review.json");JsonNode review;
        if(Files.exists(reviewFile))review=json.readTree(Files.readAllBytes(reviewFile));else {
            var reviewIntent=out.resolve("review-intent.json");assertThat(Files.exists(reviewIntent)).as("An interrupted review needs diagnosis, not a new quality vote").isFalse();
            Files.writeString(reviewIntent,json.writeValueAsString(Map.of("sheetSha256",StyledSpriteCodec.sha(raw),"rulesSha256",StyledSpriteCodec.qualityRulesSha())));
            var ai=new org.shelterconnect.api.chat.AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
            var agent=new StyledQualityAgent(new org.shelterconnect.api.chat.OpenAiResponsesClient(ai,json),ai,json);
            review=agent.review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),seeds,frames,"IDLE","north");
            Files.writeString(reviewFile,json.writeValueAsString(review));
        }
        var outcome=json.createObjectNode().put("edited",true).put("passed",review.path("passed").asBoolean())
            .put("motionDecision",review.path("motionDecision").asText()).put("providerJobId",id.toString())
            .put("sheetSha256",StyledSpriteCodec.sha(raw)).put("productionPublished",false).put("automaticApproval",false);
        outcome.set("issues",review.path("issues"));outcome.set("uncertainProperties",review.path("uncertainProperties"));
        Files.writeString(out.resolve("outcome.json"),json.writeValueAsString(outcome));
        // The test verifies that the real request/review completed. PASS is reported separately, never asserted into existence.
        assertThat(StyledSpriteCodec.frames(raw)).hasSize(9);
    }

}
