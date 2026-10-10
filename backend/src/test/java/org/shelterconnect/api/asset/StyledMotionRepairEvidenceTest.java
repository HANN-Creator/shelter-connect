package org.shelterconnect.api.asset;

import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.shelterconnect.api.chat.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class StyledMotionRepairEvidenceTest {
    final JsonMapper json=JsonMapper.builder().build();
    static final Path ROOT=Path.of("scripts/fixtures/motion-repair-v38");
    JsonNode original(String label)throws Exception{return json.readTree(Files.readAllBytes(ROOT.resolve(label+"-qualityReport.json")));}
    ObjectNode replay(String label)throws Exception {
        var r=(ObjectNode)original(label);r.put("rulesSha256",StyledSpriteCodec.qualityRulesSha());
        for(String k:List.of("rawEditReview","restoredReview"))if(r.has(k))((ObjectNode)r.path(k)).put("rulesSha256",StyledSpriteCodec.qualityRulesSha());
        return r; // Historic observations for decision replay, never claimed as fresh visual QA.
    }
    ObjectNode replayCurrentRules(JsonNode archived) {
        var copy=(ObjectNode)archived.deepCopy();bindReplay(copy);return copy;
    }
    private void bindReplay(JsonNode node) {
        if(node.isObject() && node.has("rulesSha256"))((ObjectNode)node).put("rulesSha256",StyledSpriteCodec.qualityRulesSha());
        for(var child:node)if(child.isObject() || child.isArray())bindReplay(child);
    }
    List<byte[]> seeds()throws Exception{var s=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)s.add(StyledSpriteCodec.paddedSeed(Files.readAllBytes(ROOT.resolve("seeds/"+d+".png"))));return s;}
    List<byte[]> frames(String label)throws Exception{return StyledSpriteCodec.frames(Files.readAllBytes(ROOT.resolve(label+".png")));}
    @Test void actualSourcesRemainImmutableAndPlansDoNotChangeTheirQualityVerdicts()throws Exception {
        var e=json.readTree(Files.readAllBytes(ROOT.resolve("evidence.json")));
        for(var f:e.path("hashes").properties())assertThat(StyledSpriteCodec.sha(Files.readAllBytes(ROOT.resolve(f.getKey())))).isEqualTo(f.getValue().asText());
        for(String label:List.of("idle-west","walk-south","sit-south"))assertThat(frames(label)).hasSize(9);
        var idle=replay("idle-west");var before=idle.deepCopy();assertThat(StyledIdleHold.motionOnlyFailure(idle)).isTrue();assertThat(idle).isEqualTo(before);
        var sit=replay("sit-south");before=sit.deepCopy();var plan=StyledEntryPoseRepair.plan(sit,"SIT",json);
        assertThat(plan.path("assessment").asText()).isEqualTo("CONFIRMED_ENTRY_RESTART");assertThat(sit).isEqualTo(before);
        assertThat(StyledEntryPoseRepair.plan(original("sit-south"),"SIT",json)).isNull();
        assertThat(StyledEntryPoseRepair.plan(sit,"WALK",json)).isNull();assertThat(StyledEntryPoseRepair.plan(sit,"IDLE",json)).isNull();
        var walk=replay("walk-south");assertThat(StyledWalkEvidence.eligible(walk,"WALK","south")).isTrue();
        assertThat(StyledMotionCandidate.plan(walk,"WALK",json)).isNull();assertThat(StyledConfirmedMotionRepair.plan(walk,"WALK",json)).isNull();
    }
    @Test void noStaticAlternativeOrEntryRestartCanWaiveDisputedAnatomy()throws Exception {
        for(String label:List.of("idle-west","sit-south"))for(String layer:List.of("main","rawEditReview","restoredReview")) {
            var current=replay(label);if(!layer.equals("main") && !current.has(layer))continue;
            for(String key:List.of("identity","eyes","tail","limbs","direction","palette"))for(String state:List.of("FAIL","UNCERTAIN")) {
                var r=replay(label);var target=layer.equals("main")?r:r.path(layer);
                ((ObjectNode)StyledMotionReview.property(target.path("consistencyReview"),key)).put("state",state).putArray("frames").add(0);
                assertThat(StyledIdleHold.motionOnlyFailure(r)).isFalse();assertThat(StyledEntryPoseRepair.plan(r,"SIT",json)).isNull();
            }
        }
        var sit=replay("sit-south");((ObjectNode)StyledMotionReview.property(sit.path("initialVision"),"referencePose")).putArray("frames").add(8);
        assertThat(StyledEntryPoseRepair.plan(sit,"SIT",json)).isNull();
        var idle=replay("idle-west");((ObjectNode)StyledMotionReview.property(idle.path("consistencyReview"),"idleStillness")).put("state","PASS");
        assertThat(StyledIdleHold.motionOnlyFailure(idle)).isFalse();
    }
    @Test void endpointConstraintsUseOriginalFullFramesWithoutAlteringEitherImage()throws Exception {
        var previous=frames("sit-south");var hashes=previous.stream().map(StyledSpriteCodec::sha).toList();
        var payload=json.createObjectNode().put("description","SIT transition");payload.set("first_frame",json.valueToTree(Map.of("type","base64","base64",Base64.getEncoder().encodeToString(seeds().getFirst()))));
        var original=payload.deepCopy();var bounded=StyledEntryPoseRepair.endpoints(payload,previous,json);
        assertThat(bounded.path("first_frame")).isEqualTo(original.path("first_frame"));
        assertThat(Base64.getDecoder().decode(bounded.at("/last_frame/base64").asText())).isEqualTo(previous.getLast());
        assertThat(payload).isEqualTo(original);assertThat(previous.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(hashes);
        assertThatThrownBy(()->StyledEntryPoseRepair.endpoints(payload,previous.subList(0,8),json)).isInstanceOf(AssetException.class);
        var longPrompt=payload.deepCopy().put("description","x".repeat(1000));
        assertThatThrownBy(()->StyledEntryPoseRepair.endpoints(longPrompt,previous,json)).isInstanceOf(AssetException.class);
        var rearCaption=payload.deepCopy().put("description","x".repeat(879));
        assertThat(StyledEntryPoseRepair.endpoints(rearCaption,previous,json).path("description").asText().length()).isLessThan(920);
    }
    ObjectNode clearEvidence() {
        var r=json.createObjectNode();var fs=r.putArray("frames");var ts=r.putArray("transitions");
        for(int i=0;i<9;i++) {
            fs.addObject().put("frame",i).put("tail","OCCLUDED_BY_BODY").put("evidence","Synthetic fixture, not actual visual approval.");
            ts.addObject().put("from",i).put("to",(i+1)%9).put("state","CONTINUOUS").put("evidence","Synthetic adjacent-pair observation.");
        }return r;
    }
    @Test void focusIsOneExtraEvidenceCallAndPreservesBothEarlierObservations()throws Exception {
        var ai=new AiProperties(true,"test","gpt-5.6-luna",60);var client=mock(OpenAiResponsesClient.class);
        when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"))).thenReturn(clearEvidence());
        var r=replay("walk-south");r.remove(List.of("rawEditReview","restoredReview","rawEditSha256"));var before=r.deepCopy();
        var refined=StyledWalkEvidence.refine(client,ai,json,r,seeds(),frames("walk-south"),"WALK","south");
        assertThat(refined.path("passed").asBoolean()).isTrue();assertThat(refined.path("beforeWalkEvidence")).isEqualTo(before);
        assertThat(refined.path("initialVision")).isEqualTo(before.path("initialVision"));
        assertThat(refined.path("consistencyReview")).isEqualTo(before.path("consistencyReview"));
        assertThat(StyledWalkEvidence.bound(refined)).isTrue();
        StyledWalkEvidence.refine(client,ai,json,refined,seeds(),frames("walk-south"),"WALK","south");
        verify(client,times(1)).structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"));
        ((ObjectNode)refined.path("walkEvidence")).put("rulesSha256","0".repeat(64));assertThat(StyledWalkEvidence.bound(refined)).isFalse();
    }
    @Test void missingUnclearOrWrongPairsCannotTurnIntoPassAndNoRepeatAfterTimeout()throws Exception {
        for(String mutation:List.of("missing","duplicate","seam","jump","tail","uncertain","blank")) {
            var e=clearEvidence();switch(mutation) {
                case "missing"->((tools.jackson.databind.node.ArrayNode)e.path("frames")).remove(8);
                case "duplicate"->((ObjectNode)e.at("/frames/8")).put("frame",7);
                case "seam"->((ObjectNode)e.at("/transitions/8")).put("to",8);
                case "jump"->((ObjectNode)e.at("/transitions/8")).put("state","VISIBLE_JUMP");
                case "tail"->((ObjectNode)e.at("/frames/4")).put("tail","VISIBLE_DEFECT");
                case "uncertain"->((ObjectNode)e.at("/frames/4")).put("tail","UNCERTAIN");
                case "blank"->((ObjectNode)e.at("/frames/4")).put("evidence","");
            }assertThat(StyledWalkEvidence.clear(e)).as(mutation).isFalse();
        }
        var ai=new AiProperties(true,"test","gpt-5.6-luna",60);var client=mock(OpenAiResponsesClient.class);
        when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"))).thenThrow(new AiFailure("AI_TIMEOUT"));
        var r=replay("walk-south");StyledWalkEvidence.refine(client,ai,json,r,seeds(),frames("walk-south"),"WALK","south");
        assertThat(r.path("passed").asBoolean()).isFalse();assertThat(r.at("/walkEvidence/status").asText()).isEqualTo("UNAVAILABLE");
        StyledWalkEvidence.refine(client,ai,json,r,seeds(),frames("walk-south"),"WALK","south");
        verify(client,times(1)).structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"));
        for(String property:StyledMotionReview.PROPERTIES.keySet()) {
            r=replay("walk-south");((ObjectNode)StyledMotionReview.property(r.path("initialVision"),property)).put("state","FAIL").putArray("frames").add(4);
            assertThat(StyledWalkEvidence.eligible(r,"WALK","south")).as(property).isFalse();
        }
    }
    @Test void allNineTransitionsKeepEveryOpaqueSourcePixelIncludingLastToFirst()throws Exception {
        var fs=frames("walk-south");var boards=StyledWalkEvidence.pairs(fs).values().stream().toList();
        for(int edge=0;edge<9;edge++) {
            var b=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(boards.get(edge/3)));
            for(int side=0;side<2;side++){var f=StyledSpriteCodec.motionFrame(fs.get((edge+side)%9));
                for(int y=0;y<40;y++)for(int x=0;x<40;x++)if((f.getRGB(x,y)>>>24)==255)
                    assertThat(b.getRGB(side*240+x*6,edge%3*264+24+y*6)).isEqualTo(f.getRGB(x,y));}
        }
    }
    @Test void actualProviderReceiptsKeepFailuresAndBindSuccessfulReviewToAllNineFrames()throws Exception {
        var root=ROOT.resolve("live");var receipt=json.readTree(Files.readAllBytes(root.resolve("evidence.json")));
        assertThat(receipt.path("productionChanged").asBoolean()).isFalse();
        for(var f:receipt.path("hashes").properties())assertThat(StyledSpriteCodec.sha(Files.readAllBytes(root.resolve(f.getKey())))).isEqualTo(f.getValue().asText());
        assertThat(receipt.path("pixelLabRequests").asInt()).isEqualTo(7);
        assertThat(receipt.path("receipts")).hasSize(7);
        for(String label:List.of("idle-west-static","sit-first-only","sit-first-only-edit","sit-endpoints","sit-endpoints-edit",
            "walk-loop-hidden-caption","walk-loop-visible-caption","walk-original-seam-edit")) {
            var report=json.readTree(Files.readAllBytes(root.resolve(label+"-review.json")));
            var frames=StyledSpriteCodec.frames(Files.readAllBytes(root.resolve(label+".png")));
            assertThat(report.path("reviewedFrameHashes")).isEqualTo(json.valueToTree(frames.stream().map(StyledSpriteCodec::sha).toList()));
            assertThat(report.path("passed").asBoolean()).as(label).isEqualTo(Set.of("idle-west-static","sit-endpoints-edit","walk-original-seam-edit").contains(label));
        }
        var walk=json.readTree(Files.readAllBytes(root.resolve("walk-south-focused-review.json")));
        // Historical QA is stale after a rule-catalogue update. Replay decisions only
        // on a separate in-memory copy; never relabel the archived report as current QA.
        assertThat(StyledWalkEvidence.bound(walk)).isFalse();
        assertThat(StyledWalkEvidence.bound(replayCurrentRules(walk))).isTrue();
        assertThat(walk.path("beforeWalkEvidence").path("passed").asBoolean()).isFalse();
        assertThat(json.readTree(Files.readAllBytes(root.resolve("negative-jump-focused-review.json"))).path("passed").asBoolean()).isFalse();
        // Stored real outcomes are regression evidence, not live calls or a claim that future samples always pass.
    }
    @Test void historicalOriginalReviewConflictsUseDifferentCandidatesWithoutChangingVerdicts()throws Exception {
        var root=ROOT.resolve("live/current-rule-originals");
        var archived=json.readTree(Files.readAllBytes(root.resolve("idle-west-review.json")));
        assertThat(StyledIdleHold.motionOnlyFailure(archived)).isFalse();
        var idle=replayCurrentRules(archived);var before=idle.deepCopy();
        assertThat(StyledIdleHold.motionOnlyFailure(idle)).isTrue();assertThat(idle).isEqualTo(before);
        ((ObjectNode)StyledMotionReview.property(idle.path("initialVision"),"tail")).putArray("frames").add(0);
        assertThat(StyledIdleHold.motionOnlyFailure(idle)).isFalse();
        archived=json.readTree(Files.readAllBytes(root.resolve("walk-south-review.json")));
        assertThat(StyledMotionCandidate.plan(archived,"WALK",json)).isNull();
        var walk=replayCurrentRules(archived);before=walk.deepCopy();
        assertThat(StyledMotionCandidate.plan(walk,"WALK",json).path("properties").toString()).contains("loop");assertThat(walk).isEqualTo(before);
    }
}
