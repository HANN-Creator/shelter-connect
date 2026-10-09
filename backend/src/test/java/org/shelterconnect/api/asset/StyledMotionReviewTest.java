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

class StyledMotionReviewTest {
    final JsonMapper json=JsonMapper.builder().build();
    final Path root=Path.of("scripts/fixtures/motion-review-v22");
    final AiProperties ai=new AiProperties(true,"test-key","gpt-5.6-luna",60);
    JsonNode observation(String property,String state) {
        var out=json.createObjectNode().put("observedMotion",state.equals("PASS")?"STILL":"OTHER_MOVEMENT");var list=out.putArray("properties");
        for(String key:new TreeSet<>(StyledMotionReview.PROPERTIES.keySet())) {
            var p=list.addObject().put("property",key).put("state",key.equals(property)?state:"PASS").put("evidence","Synthetic unit observation, not actual model quality.");
            var f=p.putArray("frames");if(key.equals(property) && state.equals("FAIL"))f.add(8);
        }return out;
    }
    JsonNode run(OpenAiResponsesClient client,String file,String action)throws Exception {
        var seed=StyledSpriteCodec.paddedSeed(Files.readAllBytes(root.resolve("seeds/west.png")));
        return new StyledQualityAgent(client,ai,json).review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),Collections.nCopies(4,seed),
            StyledSpriteCodec.frames(Files.readAllBytes(root.resolve(file))),action,"west");
    }
    @Test void uncertainOrDisagreeingPropertiesNeverBecomeFailureLabelsOrPasses()throws Exception {
        for(String second:List.of("PASS","FAIL","UNCERTAIN")) {
            var client=mock(OpenAiResponsesClient.class);
            when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium")))
                .thenReturn(observation("idleStillness","FAIL"),observation("idleStillness",second));
            var r=run(client,"idle-west.png","IDLE");
            assertThat(r.path("passed").asBoolean()).isFalse();
            assertThat(StyledMotionReview.unresolved(r)).isEqualTo(!second.equals("FAIL"));
            assertThat(r.path("issues").toString().contains("IDLE_MOTION")).isEqualTo(second.equals("FAIL"));
            verify(client,times(2)).structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"));
        }
    }
    @Test void visibleEntryShadingIsDiagnosticAndCannotBypassIdentityOrHashBindings()throws Exception {
        var client=mock(OpenAiResponsesClient.class);when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"))).thenReturn(observation("","PASS"));
        var r=(ObjectNode)run(client,"idle-west.png","IDLE");
        var frames=StyledSpriteCodec.frames(Files.readAllBytes(root.resolve("idle-west.png")));
        var seed=StyledSpriteCodec.paddedSeed(Files.readAllBytes(root.resolve("seeds/west.png")));
        StyledMotionReview.bind(r,seed,frames,json);
        assertThat(r.path("firstFrameUnchanged").asBoolean()).isFalse();assertThat(r.path("passed").asBoolean()).isTrue();assertThat(StyledMotionReview.boundPass(r)).isTrue();
        r.put("motionSeedSha256","0".repeat(64));assertThat(StyledMotionReview.boundPass(r)).isFalse();
        verify(client,times(1)).structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"));
        var bad=observation("identity","FAIL");when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"))).thenReturn(bad);
        assertThat(run(client,"idle-west.png","IDLE").path("issues").toString()).contains("IDENTITY_DRIFT");
    }
    @Test void exactClippingAndStillWalkBlockEvenWhenVisionPasses()throws Exception {
        var client=mock(OpenAiResponsesClient.class);when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"))).thenReturn(observation("","PASS"));
        var clipped=run(client,"clipped-last.png","IDLE");assertThat(clipped.path("issues").toString()).contains("CANVAS_CLIPPING");assertThat(clipped.path("edgeFrames").toString()).isEqualTo("[8]");
        assertThat(run(client,"idle-west.png","WALK").path("issues").toString()).contains("ACTION_MISSING");
    }
    @Test void onlyUnsupportedWalkingCanResolveWithIndependentCleanObservation()throws Exception {
        var frames=StyledSpriteCodec.frames(Files.readAllBytes(root.resolve("idle-west.png")));
        var lower=StyledMotionReview.lowerBodyEvidence(frames,json);var a=(ObjectNode)observation("idleStillness","FAIL");a.put("observedMotion","WALK_RUN");
        var b=observation("","PASS");assertThat(StyledMotionReview.resolveUnsupportedWalk("IDLE",a,b,lower)).isTrue();
        assertThat(StyledMotionReview.resolveUnsupportedWalk("WALK",a,b,lower)).isFalse();
        assertThat(StyledMotionReview.resolveUnsupportedWalk("IDLE",a,observation("palette","FAIL"),lower)).isFalse();
        a.put("observedMotion","TAIL_MOVEMENT");assertThat(StyledMotionReview.resolveUnsupportedWalk("IDLE",a,b,lower)).isFalse();
        a.put("observedMotion","WALK_RUN");var north=StyledMotionReview.lowerBodyEvidence(StyledSpriteCodec.frames(Files.readAllBytes(root.resolve("idle-north.png"))),json);
        assertThat(StyledMotionReview.resolveUnsupportedWalk("IDLE",a,b,north)).isFalse();
        var client=mock(OpenAiResponsesClient.class);when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"))).thenReturn(a,a);
        var uncertain=run(client,"idle-west.png","IDLE");assertThat(StyledMotionReview.unresolved(uncertain)).isTrue();
        assertThat(uncertain.path("issues").isEmpty()).isTrue();
    }
    @Test void paletteReconciliationCannotWaiveFeatureDefectsOrLargeColorChanges()throws Exception {
        var rules=StyledSpriteCodec.qualityRules(json);var first=observation("palette","FAIL");var second=observation("","PASS");
        var color=StyledMotionReview.colorEvidence(StyledSpriteCodec.frames(Files.readAllBytes(root.resolve("idle-west.png"))),json);
        assertThat(StyledMotionReview.resolveSubtlePalette(first,second,color,rules)).isTrue();
        assertThat(StyledMotionReview.resolveSubtlePalette(first,observation("identity","FAIL"),color,rules)).isFalse();
        assertThat(StyledMotionReview.resolveSubtlePalette(first,first,color,rules)).isFalse();
        var face=StyledMotionReview.colorEvidence(StyledSpriteCodec.frames(Files.readAllBytes(root.resolve("face-flicker.png"))),json);
        assertThat(StyledMotionReview.resolveSubtlePalette(first,second,face,rules)).isFalse();
        ((ObjectNode)StyledMotionReview.property(first,"eyes")).put("state","FAIL");
        assertThat(StyledMotionReview.resolveSubtlePalette(first,second,color,rules)).isFalse();
    }
    @Test void referenceAndTemporalBoardsContainAllUnmodifiedNativePixels()throws Exception {
        var seed=StyledSpriteCodec.paddedSeed(Files.readAllBytes(root.resolve("seeds/west.png")));
        var frames=StyledSpriteCodec.frames(Files.readAllBytes(root.resolve("idle-west.png")));var hashes=frames.stream().map(StyledSpriteCodec::sha).toList();
        var images=StyledMotionReview.images(seed,frames);assertThat(images).hasSize(2);
        var temporal=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(images.values().stream().skip(1).findFirst().orElseThrow()));
        for(int i=0;i<9;i++){var source=StyledSpriteCodec.motionFrame(frames.get(i));for(int y=0;y<40;y++)for(int x=0;x<40;x++)if((source.getRGB(x,y)>>>24)==255)
            assertThat(temporal.getRGB(i%3*240+x*6,i/3*264+24+y*6)).isEqualTo(source.getRGB(x,y));}
        assertThat(frames.stream().map(StyledSpriteCodec::sha).toList()).isEqualTo(hashes);
    }
    @Test void realUnchangedImagesUseOneBoundedProductionReviewWithoutRerolls()throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("MOTION_CONFLICT_LIVE_APPROVED")));
        var liveAi=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
        var agent=new StyledQualityAgent(new OpenAiResponsesClient(liveAi,json),liveAi,json);
        var out=Path.of(System.getenv("MOTION_CONFLICT_LIVE_OUTPUT"));Files.createDirectories(out);
        var failures=new ArrayList<String>();
        for(var c:json.readTree(Files.readAllBytes(root.resolve("evidence.json"))).path("cases")) {
            String id=c.path("id").asText();var bytes=Files.readAllBytes(root.resolve(c.path("file").asText()));var seed=Files.readAllBytes(root.resolve(c.path("seed").asText()));
            assertThat(StyledSpriteCodec.sha(bytes)).isEqualTo(c.path("fileSha256").asText());assertThat(StyledSpriteCodec.sha(seed)).isEqualTo(c.path("seedSha256").asText());
            if(StyledSpriteCodec.motionFrame(seed).getWidth()==32)seed=StyledSpriteCodec.paddedSeed(seed);
            var saved=out.resolve(id+".json");var intent=out.resolve(id+"-intent.json");JsonNode r;
            if(Files.exists(saved))r=json.readTree(Files.readAllBytes(saved));else {
                assertThat(Files.exists(intent)).as("Never rerun an unknown paid call").isFalse();Files.write(intent,json.writeValueAsBytes(Map.of("case",c,"rulesSha256",StyledSpriteCodec.qualityRulesSha())),StandardOpenOption.CREATE_NEW);
                r=agent.review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),Collections.nCopies(4,seed),StyledSpriteCodec.frames(bytes),c.path("action").asText(),c.path("direction").asText());
                Files.write(saved,json.writeValueAsBytes(r),StandardOpenOption.CREATE_NEW);
            }
            assertThat(r.path("rulesSha256").asText()).isEqualTo(StyledSpriteCodec.qualityRulesSha());
            System.out.println(id+": "+r.path("motionDecision")+" "+r.path("issues")+" uncertain="+r.path("uncertainProperties"));
            String expected=c.path("expected").asText();
            if(id.equals("idle-north")) {
                // Retain the original failed positive label and its explicit visual adjudication, not a provider reroll.
                var adjudication=json.readTree(Files.readAllBytes(root.resolve("adjudication.json")));
                assertThat(adjudication.path("imageSha256").asText()).isEqualTo(StyledSpriteCodec.sha(bytes));
                expected=adjudication.path("finalExpected").asText();
            }
            if(expected.equals("PASS") && !r.path("passed").asBoolean())failures.add(id);
            if(expected.equals("BLOCK") && r.path("passed").asBoolean())failures.add(id);
        }
        assertThat(failures).as("Preserve every real outcome; do not reroll failed cases").isEmpty();
    }
    @Test void actualReceiptsPreserveTrueTailMotionDespiteSmallOuterAlphaChange()throws Exception {
        var adjudication=json.readTree(Files.readAllBytes(root.resolve("adjudication.json")));
        var bytes=Files.readAllBytes(root.resolve("actual-reviews/idle-north.json"));assertThat(StyledSpriteCodec.sha(bytes)).isEqualTo(adjudication.path("reviewSha256").asText());
        var r=json.readTree(bytes);assertThat(r.path("motionDecision").asText()).isEqualTo("CONFIRMED_DEFECT");
        assertThat(r.path("issues").toString()).contains("IDLE_MOTION","IDENTITY_DRIFT");
        assertThat(StyledMotionReview.property(r.path("initialVision"),"idleStillness").path("state").asText()).isEqualTo("FAIL");
        assertThat(StyledMotionReview.property(r.path("consistencyReview"),"idleStillness").path("state").asText()).isEqualTo("FAIL");
        for(String name:List.of("idle-south","idle-west","genuine-walk"))assertThat(json.readTree(Files.readAllBytes(root.resolve("actual-reviews/"+name+".json"))).path("passed").asBoolean()).isTrue();
        for(String name:List.of("clipped-last","face-flicker","still-is-not-walk"))assertThat(json.readTree(Files.readAllBytes(root.resolve("actual-reviews/"+name+".json"))).path("passed").asBoolean()).isFalse();
    }
    @Test void actualPackagedPaletteDisagreementHasNarrowMeasuredResolution()throws Exception {
        var previous=json.readTree(Files.readAllBytes(root.resolve("package-v23/idle-west.json")));
        assertThat(previous.path("motionDecision").asText()).isEqualTo("UNCERTAIN");
        assertThat(previous.path("uncertainProperties").toString()).isEqualTo("[\"palette\"]");
        var frames=StyledSpriteCodec.frames(Files.readAllBytes(root.resolve("idle-west.png")));
        assertThat(StyledMotionReview.resolveSubtlePalette(previous.path("initialVision"),previous.path("consistencyReview"),
            StyledMotionReview.colorEvidence(frames,json),StyledSpriteCodec.qualityRules(json))).isTrue();
        for(String id:List.of("idle-south","idle-west","genuine-walk"))assertThat(json.readTree(Files.readAllBytes(root.resolve("package-v24/"+id+".json"))).path("passed").asBoolean()).isTrue();
        for(String id:List.of("idle-north","clipped-last","face-flicker","still-is-not-walk"))assertThat(json.readTree(Files.readAllBytes(root.resolve("package-v24/"+id+".json"))).path("passed").asBoolean()).isFalse();
    }
}
