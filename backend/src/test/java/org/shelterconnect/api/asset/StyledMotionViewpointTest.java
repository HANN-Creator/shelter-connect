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

class StyledMotionViewpointTest {
    final JsonMapper json=JsonMapper.builder().build();
    final Path root=Path.of("scripts/fixtures/rear-motion-v35");
    final AiProperties ai=new AiProperties(true,"test-key","gpt-5.6-luna",60);
    List<byte[]> seeds() throws Exception {
        var result=new ArrayList<byte[]>();for(String d:StyledSpriteCodec.DIRECTIONS)result.add(Files.readAllBytes(root.resolve("seeds/"+d+".png")));return result;
    }
    JsonNode vote(String property,String state) {
        var result=json.createObjectNode().put("observedMotion","STILL");var p=result.putArray("properties");
        for(String key:new TreeSet<>(StyledMotionReview.PROPERTIES.keySet())) {
            var item=p.addObject().put("property",key).put("state",key.equals(property)?state:"PASS").put("evidence","Synthetic contract regression; not a real visual verdict.");
            var frames=item.putArray("frames");if(key.equals(property))frames.add(1);
        }return result;
    }
    @Test void actualFailureAndAllOriginalFramesAreHashBound() throws Exception {
        var receipt=json.readTree(Files.readAllBytes(root.resolve("evidence.json")));
        for(var e:receipt.path("hashes").properties())assertThat(StyledSpriteCodec.sha(Files.readAllBytes(root.resolve(e.getKey())))).isEqualTo(e.getValue().asText());
        var failed=json.readTree(Files.readAllBytes(root.resolve("previous-north-review.json")));
        assertThat(failed.path("motionDecision").asText()).isEqualTo("UNCERTAIN");
        assertThat(StyledMotionReview.property(failed.path("initialVision"),"eyes").path("state").asText()).isEqualTo("FAIL");
        assertThat(StyledMotionReview.property(failed.path("consistencyReview"),"eyes").path("state").asText()).isEqualTo("FAIL");
        assertThat(StyledSpriteCodec.frames(Files.readAllBytes(root.resolve("idle-north.png")))).hasSize(9);
    }
    @Test void viewKeyAndTimelineRetainEveryOpaqueNativePixel() throws Exception {
        var seeds=seeds();var frames=StyledSpriteCodec.frames(Files.readAllBytes(root.resolve("idle-north.png")));
        var boards=StyledMotionReview.images(seeds,frames,"north",StyledSpriteCodec.qualityRules(json));assertThat(boards).hasSize(3);
        var key=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(boards.values().iterator().next()));
        for(int i=0;i<4;i++)assertPixels(key,StyledSpriteCodec.motionFrame(StyledSpriteCodec.paddedSeed(seeds.get(i))),i*240,24);
        var temporal=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(boards.values().stream().skip(2).findFirst().orElseThrow()));
        for(int i=0;i<9;i++)assertPixels(temporal,StyledSpriteCodec.motionFrame(frames.get(i)),i%3*240,i/3*264+24);
    }
    void assertPixels(java.awt.image.BufferedImage board,java.awt.image.BufferedImage source,int left,int top) {
        for(int y=0;y<40;y++)for(int x=0;x<40;x++)if((source.getRGB(x,y)>>>24)==255)
            assertThat(board.getRGB(left+x*6,top+y*6)).isEqualTo(source.getRGB(x,y));
    }
    @Test void bothObservationsReceiveCameraContextWithoutWaivingDefectsOrUncertainty() throws Exception {
        for(String state:List.of("FAIL","UNCERTAIN")) {
            var client=mock(OpenAiResponsesClient.class);
            when(client.structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"))).thenAnswer(call->{
                String task=call.getArgument(1);assertThat(task).contains("NORTH REAR","NORTH is REAR facing away","front face appearing in a NORTH clip is a direction/identity defect");
                Map<String,byte[]> images=call.getArgument(2);assertThat(images).hasSize(3);assertThat(images.keySet().iterator().next()).startsWith("VIEW KEY");return vote("direction",state);
            });
            var r=new StyledQualityAgent(client,ai,json).review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),seeds(),StyledSpriteCodec.frames(Files.readAllBytes(root.resolve("idle-north.png"))),"IDLE","north");
            assertThat(r.path("passed").asBoolean()).isFalse();assertThat(r.path("viewpointProtocolVersion").asText()).isEqualTo("camera-view-context-v1");
            assertThat(r.path("motionDecision").asText()).isEqualTo(state.equals("FAIL")?"CONFIRMED_DEFECT":"UNCERTAIN");
            verify(client,times(2)).structuredImagesWithReasoning(anyString(),anyString(),anyMap(),anyMap(),eq("medium"));
        }
    }
    @Test void northRepairIncludesSameRearRuleWithoutChangingFrames() throws Exception {
        var frames=StyledSpriteCodec.frames(Files.readAllBytes(root.resolve("idle-north.png")));
        var r=json.createObjectNode().put("note","Synthetic confirmed tail defect");r.putArray("issues").add("TAIL_CARRIAGE");r.putArray("frames").add(1);r.putArray("edgeFrames");
        for(String action:List.of("IDLE","WALK","SIT")) {
            var p=StyledRecovery.motionPayload(json,frames,action,"north",r,42);
            assertThat(p.path("description").asText()).contains(StyledSpriteCodec.qualityRules(json).path("rearView").asText()).hasSizeLessThanOrEqualTo(2000);
            assertThat(p.path("frames").size()).isEqualTo(9);
            for(int i=0;i<9;i++)assertThat(Base64.getDecoder().decode(p.path("frames").get(i).at("/image/base64").asText())).isEqualTo(frames.get(i));
        }
    }
    @Test void actualViewCorrectionDoesNotRelabelUnrelatedQualityConcernsAsPass() throws Exception {
        var rear=json.readTree(Files.readAllBytes(root.resolve("actual-reviews/rear.json")));
        for(String observation:List.of("initialVision","consistencyReview"))for(String p:List.of("direction","eyes"))
            assertThat(StyledMotionReview.property(rear.path(observation),p).path("state").asText()).isEqualTo("PASS");
        assertThat(rear.path("motionDecision").asText()).isEqualTo("UNCERTAIN");assertThat(rear.path("passed").asBoolean()).isFalse();
        var front=json.readTree(Files.readAllBytes(root.resolve("actual-reviews/front-padded.json")));
        assertThat(front.path("passed").asBoolean()).isTrue();
        var wrong=json.readTree(Files.readAllBytes(root.resolve("actual-reviews/front-is-not-rear.json")));
        assertThat(wrong.path("passed").asBoolean()).isFalse();assertThat(wrong.path("issues").toString()).contains("DIRECTION_DRIFT");
    }
    @Test void liveRearAndFrontContrastUseOneBoundedReviewEach() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeTrue("true".equals(System.getenv("MOTION_VIEW_LIVE_APPROVED")));
        var props=new AiProperties(true,System.getenv("OPENAI_API_KEY"),"gpt-5.6-luna",60);
        var agent=new StyledQualityAgent(new OpenAiResponsesClient(props,json),props,json);
        var out=Path.of(System.getenv("MOTION_VIEW_LIVE_OUTPUT"));Files.createDirectories(out);var failures=new ArrayList<String>();
        // The deployed worker supplies 40px padded seeds. Keep the raw fixture unchanged.
        var actualSeeds=seeds().stream().map(StyledSpriteCodec::paddedSeed).toList();
        for(String name:List.of("rear","front-padded","front-is-not-rear")) {
            String source=name.equals("rear")?"north":"south",direction=name.equals("front-padded")?"south":"north";
            byte[] sheet=Files.readAllBytes(root.resolve("idle-"+source+".png"));var saved=out.resolve(name+".json");var intent=out.resolve(name+"-intent.json");JsonNode report;
            if(Files.exists(saved))report=json.readTree(Files.readAllBytes(saved));else {
                assertThat(Files.exists(intent)).as("Unknown paid call must not be blindly rerun").isFalse();
                Files.write(intent,json.writeValueAsBytes(Map.of("sourceSha256",StyledSpriteCodec.sha(sheet),"requestedDirection",direction,"rulesSha256",StyledSpriteCodec.qualityRulesSha())),StandardOpenOption.CREATE_NEW);
                report=agent.review(json.readTree("{\"tailCarriage\":\"UNKNOWN\"}"),actualSeeds,StyledSpriteCodec.frames(sheet),"IDLE",direction);
                Files.write(saved,json.writeValueAsBytes(report),StandardOpenOption.CREATE_NEW);
            }
            assertThat(report.path("rulesSha256").asText()).isEqualTo(StyledSpriteCodec.qualityRulesSha());
            System.out.println(name+": "+report.path("motionDecision")+" issues="+report.path("issues")+" uncertainty="+report.path("uncertainProperties"));
            if(name.equals("front-is-not-rear")) {if(report.path("passed").asBoolean() || !report.path("issues").toString().contains("DIRECTION_DRIFT"))failures.add(name);}
            else if(name.equals("rear")) {
                // Removing a false eyes claim must not waive the independently observed coat variation.
                for(String key:List.of("initialVision","consistencyReview"))if(report.has(key))for(String property:List.of("direction","eyes"))
                    if(!StyledMotionReview.property(report.path(key),property).path("state").asText().equals("PASS"))failures.add(name+"/"+property);
                if(!report.path("passed").asBoolean() && StyledMotionCandidate.plan(report,"IDLE",json)==null)failures.add(name+"/no-bounded-repair-target");
            } else if(!report.path("passed").asBoolean())failures.add(name);
        }
        assertThat(failures).as("Retain every real result, never reroll until PASS").isEmpty();
    }
}
